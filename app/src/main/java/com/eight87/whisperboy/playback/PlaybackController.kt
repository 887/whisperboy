package com.eight87.whisperboy.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.eight87.whisperboy.data.library.BookEntity
import com.eight87.whisperboy.data.library.BookSource
import com.eight87.whisperboy.data.library.ChapterEntity
import com.eight87.whisperboy.data.library.ChapterSource
import com.eight87.whisperboy.data.playback.PlaybackSettings
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Read-only projection of the playback session for UI.
 *
 * Phase R.A pattern (narrow interfaces): composables / mini-players that only need to *observe*
 * state take this, never [PlaybackController] directly. The split lets a future mini-player /
 * widget consume the same Flow without inheriting transport-command authority.
 */
interface NowPlayingState {
    val state: StateFlow<PlaybackUiState>
}

/**
 * Imperative transport against the active session. Phase F.1's player surface consumes this
 * exclusively for user actions; the controller resolves each call against the live
 * [androidx.media3.session.MediaController] when connected, and no-ops cleanly when not.
 *
 * Rewind / forward seek by a user-configurable number of seconds (Phase F.3, defaults 30s);
 * actual values flow through [PlaybackSettings].
 */
interface TransportCommands {
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionInBookMs: Long)
    suspend fun rewind()
    suspend fun forward()
    suspend fun nextChapter()
    suspend fun prevChapter()
    /**
     * Jump directly to a chapter, starting at the chapter head.
     *
     * MultiFile books (one MediaItem per chapter): dispatches as `c.seekTo(chapterIndex, 0)`.
     * SingleFile books (one MediaItem for the whole file): dispatches as
     * `c.seekTo(0, positionInBookMs)`. The caller passes BOTH so [PlaybackController]
     * doesn't have to re-detect which mode it's in.
     */
    suspend fun playChapter(chapterIndex: Int, positionInBookMs: Long)
    suspend fun setSpeed(speed: Float)
    suspend fun setSkipSilence(enabled: Boolean)
    suspend fun setGain(gainDb: Float)
}

/**
 * Commands that switch the player to a different book. Separated from [TransportCommands]
 * (R.A.4 / R.C.1) so a now-playing bar that *only* observes current state and toggles play/pause
 * doesn't depend on book-loading authority.
 */
interface BookCommands {
    suspend fun playBook(bookId: String)
    suspend fun playBookFromPosition(bookId: String, positionInBookMs: Long)
}

/**
 * Phase G — narrow handle the sleep-timer service uses to drive the underlying
 * [androidx.media3.session.MediaController]. Kept separate from [TransportCommands] so the
 * timer can ramp volume / pause without inheriting transport-button authority, and so the test
 * surface for [AndroidSleepTimer] doesn't need a full transport mock.
 *
 *  - [setVolumeNow] — set the player's volume (0.0..1.0). Called on Main thread internally.
 *  - [pauseNow] — pause the player. Called when the timer fires.
 *  - [currentPositionMs] — read current playback position (for the auto-bookmark at fire).
 *  - [currentBookId] — read the currently-targeted bookId (for the auto-bookmark).
 *  - [currentChapterId] — current chapter id, if any (for the auto-bookmark).
 *  - [mediaItemTransitions] — Flow of `Unit` ticks each time the player transitions media items
 *    (chapter boundaries). Used by [SleepTimerMode.EndOfChapter] to know when to pause.
 */
internal interface PlayerHandle {
    suspend fun setVolumeNow(volume: Float)
    suspend fun pauseNow()
    suspend fun currentPositionMs(): Long
    suspend fun currentBookId(): String?
    suspend fun currentChapterId(): String?
    val mediaItemTransitions: kotlinx.coroutines.flow.SharedFlow<Unit>
}

/**
 * Sleep timer commands — Phase G surface. Narrow facet (R.A pattern): the player top-app-bar
 * sleep button + bottom-sheet take only this interface, never the concrete
 * [com.eight87.whisperboy.playback.AndroidSleepTimer].
 *
 *  - [arm] — start a new timer in the given mode. Replaces any active timer.
 *  - [cancel] — stop the active timer (or unregister the post-fire shake window). Restores volume
 *    to 1.0 if a fade-out was in flight.
 *  - [state] — observable [SleepTimerState] for the UI (button label + bottom-sheet header).
 */
interface SleepTimerCommands {
    suspend fun arm(mode: SleepTimerMode)
    suspend fun cancel()
    val state: kotlinx.coroutines.flow.StateFlow<SleepTimerState>
}

/**
 * UI-side wrapper around Media3's [MediaController]. Owns:
 *
 *  - Async connection to the running [PlaybackService] via `SessionToken` + `MediaController.Builder`.
 *  - Projection of [Player.Listener] events into [PlaybackUiState] (event-driven: book / chapter /
 *    playing / speed / etc.) plus the **separate** position ticker (R.C.5) at ~250ms while playing.
 *  - Implementing [NowPlayingState] / [TransportCommands] / [BookCommands] / [SleepTimerCommands]
 *    over that one connection.
 *
 * Phase R.A.2: this class is `internal`; module-external consumers (composables) see only the four
 * narrow interfaces above. [com.eight87.whisperboy.AppGraph] exposes them separately.
 *
 * Phase R.C.5: the position ticker is a separate coroutine from the listener-driven projection —
 * a position tick does NOT recompute chapter / metadata. The ticker runs only while playing and
 * idles cleanly on pause.
 */
internal class PlaybackController(
    context: Context,
    private val bookSource: BookSource,
    private val chapterSource: ChapterSource,
    private val playbackSettings: PlaybackSettings,
    private val applicationScope: CoroutineScope,
) : NowPlayingState, TransportCommands, BookCommands, PlayerHandle {

    private val appContext = context.applicationContext

    private val sessionToken = SessionToken(
        appContext,
        ComponentName(appContext, PlaybackService::class.java),
    )

    /** Holds the connected controller once the async build resolves. Read on Main thread only. */
    @Volatile
    private var controller: MediaController? = null

    /** Connection state — true once [MediaController.Builder.buildAsync] resolves. */
    private val connected = MutableStateFlow(false)

    /**
     * Phase R — pending transport actions queued while the controller is disconnected. Flushed
     * from [bindController] once the new controller resolves. Bounded by [MAX_PENDING_ACTIONS]
     * so a permanently-dead service doesn't grow this unboundedly. Thread-safe by construction
     * (touched from arbitrary coroutine threads via [onMain] and from the Main thread on flush).
     */
    private val pendingActions = ConcurrentLinkedQueue<(MediaController) -> Unit>()

    /** Reconnect retry count; reset to 0 on a successful bind. Caps at [MAX_RECONNECT_ATTEMPTS]. */
    @Volatile private var reconnectAttempts: Int = 0

    /** Set once [release] runs so any in-flight reconnect attempt no-ops cleanly. */
    @Volatile private var released: Boolean = false

    /** Currently-targeted book id. `null` means "no playback session yet". */
    private val targetedBookId = MutableStateFlow<String?>(null)

    /** Listener-driven snapshot: playing / speed / skipSilence / gain / mediaId / chapterIndex. */
    private val playerSnapshot = MutableStateFlow(PlayerSnapshot())

    /** Position ticker output. Decoupled from [playerSnapshot] so chapter projection doesn't churn. */
    private val positionMs = MutableStateFlow(0L)

    /**
     * Phase G — broadcast on every [Player.Listener.onMediaItemTransition] so the sleep timer's
     * "end of chapter" mode can pause when the current chapter ends. `replay = 0` (event-only, no
     * late-subscriber replay) and `extraBufferCapacity = 1` so a fast transition isn't dropped if
     * the collector is briefly behind.
     */
    override val mediaItemTransitions: kotlinx.coroutines.flow.SharedFlow<Unit>
        get() = _mediaItemTransitions
    private val _mediaItemTransitions = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )

    /**
     * Current values for the three user-tunable seek knobs (F.3 + F.4). Updated by a settings
     * collector started in [init]; read on the Main thread inside [rewind] / [forward] / [play].
     * `@Volatile` is enough — these are written by a single collector coroutine, read by the
     * transport coroutines, and tearing a 32-bit Int read on Dalvik / ART is not a concern.
     */
    @Volatile private var rewindMs: Long = DEFAULT_SEEK_MS
    @Volatile private var forwardMs: Long = DEFAULT_SEEK_MS
    @Volatile private var autoRewindMs: Long = DEFAULT_AUTO_REWIND_MS

    /**
     * Epoch-ms at which the player most recently transitioned playing→paused. Cleared on the
     * next [play] call. Drives Phase F.4: if the gap exceeds [AUTO_REWIND_THRESHOLD_MS] when the
     * user resumes, [play] silently rewinds by [autoRewindMs] before issuing `c.play()`.
     */
    @Volatile private var pausedAtEpochMs: Long? = null

    /**
     * Phase R — [MediaController.Listener] that flips [connected] back to false and triggers
     * a rebind when the underlying binder dies (service killed for OOM / force-stop / low-mem).
     * Without this, [controller] would stay non-null pointing at a dead binder, every
     * transport command would silently no-op, and the mini-player would freeze with `connected
     * = true`. The flush of pending actions on rebind keeps the next user tap from being lost.
     *
     * Declared before [init] so it's initialised by the time [bindController] runs (Kotlin
     * field-init order: declarations before `init` resolve before init-block bodies execute).
     */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(c: MediaController) {
            controller = null
            connected.value = false
            tickerJob?.cancel()
            if (!released) bindController()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerSnapshot.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) {
                // Going playing→paused: record the timestamp so a >5min idle bump on resume can
                // trigger the auto-rewind (F.4). We use `System.currentTimeMillis()` for the
                // wall-clock delta because the threshold is in real seconds, not media time.
                pausedAtEpochMs = System.currentTimeMillis()
                // Phase P.7 — save the resume point on pause. Voice's pattern: save on real
                // events, not on a 1Hz timer. Cheap (single UPDATE row on the books table) and
                // survives process death without an in-flight write being orphaned.
                saveCurrentPosition()
            }
            // Re-arm or stop the ticker on play state changes.
            restartTickerIfNeeded()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playerSnapshot.update {
                it.copy(
                    currentMediaId = mediaItem?.mediaId,
                    currentItemIndex = controller?.currentMediaItemIndex ?: 0,
                )
            }
            // A media-item transition is an event-driven projection tick (R.C.5).
            controller?.let { c -> positionMs.value = c.currentPosition }
            // Phase P.7 — save the resume point at the chapter boundary. The new chapter index
            // is already on the snapshot above; position is whatever the controller reports
            // just after the transition (typically ~0 for a natural chapter-end transition,
            // potentially non-zero for a seek-driven transition — either way, that's the
            // correct "where the user is" value to persist).
            saveCurrentPosition()
            // Phase G — broadcast the chapter boundary to the sleep timer. tryEmit is fine here
            // because the SharedFlow has `extraBufferCapacity = 1` and we never miss a meaningful
            // edge (the collector is interested in *that there was* a transition).
            _mediaItemTransitions.tryEmit(Unit)
        }

        override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) {
            playerSnapshot.update { it.copy(speed = p.speed) }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Whisperboy was silently no-op'ing on transport when the underlying source failed
            // (stale SAF URI, decoder error, missing file). Surface it loudly to logcat so
            // "play button does nothing" reports come with diagnostic breadcrumbs.
            android.util.Log.e(
                "whisperboy.playback",
                "Player error code=${error.errorCode} (${error.errorCodeName}): ${error.message}",
                error,
            )
        }

        override fun onPlaybackStateChanged(state: Int) {
            android.util.Log.d(
                "whisperboy.playback",
                "playbackState=${stateName(state)} count=${controller?.mediaItemCount} idx=${controller?.currentMediaItemIndex} pwr=${controller?.playWhenReady}",
            )
        }
    }

    private fun stateName(s: Int): String = when (s) {
        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
        androidx.media3.common.Player.STATE_READY -> "READY"
        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
        else -> "?($s)"
    }

    /**
     * Phase P.7 — observer on [ProcessLifecycleOwner] that calls [saveCurrentPosition] on
     * `ON_STOP` (app goes to background — Home, recent apps, screen off). Registered in [init]
     * and removed in [release] so this doesn't leak across the activity / service lifecycle.
     */
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            saveCurrentPosition()
        }
    }
    private val processLifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle

    init {
        // Phase P.7 — register the background-save observer. The ProcessLifecycleOwner emits
        // ON_STOP when the last visible activity goes invisible (Home, lock, app-switch).
        // Reigstered on Main per Lifecycle's contract; the application context wires up before
        // the first frame so this lands during app graph construction without races.
        processLifecycle.addObserver(processLifecycleObserver)

        // F.3 + F.4 — keep the three seek-seconds knobs synced from DataStore. Cold-path values
        // that change rarely; a single collector per field is plenty (no need to combine() into a
        // hot Flow that would re-emit on every position tick).
        applicationScope.launch {
            playbackSettings.rewindSeconds.collect { seconds ->
                rewindMs = seconds.toLong() * 1000L
            }
        }
        applicationScope.launch {
            playbackSettings.forwardSeconds.collect { seconds ->
                forwardMs = seconds.toLong() * 1000L
            }
        }
        applicationScope.launch {
            playbackSettings.autoRewindSeconds.collect { seconds ->
                autoRewindMs = seconds.toLong() * 1000L
            }
        }

        bindController()

        // Phase P.7 (1Hz throttled save) — Voice-pattern continuous save while playing. Event-
        // driven saves (chapter transition, pause, ON_STOP, service onDestroy) still fire for
        // guaranteed coverage, but a hard process-kill mid-chapter would otherwise lose up to
        // the whole chapter; sampling the existing 250ms position ticker at 1Hz limits worst-
        // case loss to ~1 second of audio at the cost of one row UPDATE per second. The
        // `isPlaying` guard avoids Room writes while paused (the pause-event save already
        // captured that position).
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        applicationScope.launch {
            positionMs.sample(POSITION_SAVE_INTERVAL_MS).collect {
                if (playerSnapshot.value.isPlaying) {
                    // Crash fix — this collector runs on the application scope's default
                    // dispatcher, NOT Main. Calling [saveCurrentPosition] directly here would
                    // read [MediaController.currentMediaItemIndex] / `currentPosition` off the
                    // wrong thread, which Media3 enforces with `IllegalStateException`
                    // ("MediaController method is called from a wrong thread"). Route the
                    // controller read through [onMain] — the same path every transport command
                    // already uses — so the controller fields are read on Main and only the
                    // Room write fans back out onto the application scope.
                    onMain { c -> saveCurrentPositionFromMain(c) }
                }
            }
        }
    }

    /**
     * Phase R — async bind of the [MediaController] against [sessionToken]. Idempotent: a second
     * call while a bind is in flight is a no-op (guarded via [reconnectAttempts] semantics);
     * a call after [release] is a no-op via [released]. Called from [init] and from
     * [controllerListener.onDisconnected] when the service dies.
     *
     * On success: attaches [playerListener], snapshots state, flushes [pendingActions], flips
     * [connected] to true, restarts the position ticker. On failure: schedules a retry with
     * linear backoff up to [MAX_RECONNECT_ATTEMPTS]; once exhausted, [connected] stays false
     * and the UI surfaces Loading indefinitely (caller can navigate back).
     */
    private fun bindController() {
        if (released) return
        val builderFuture = MediaController.Builder(appContext, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        builderFuture.addListener(
            {
                try {
                    val c = builderFuture.get()
                    c.addListener(playerListener)
                    controller = c
                    reconnectAttempts = 0
                    // Snapshot the initial state once the controller resolves.
                    playerSnapshot.value = PlayerSnapshot(
                        isPlaying = c.isPlaying,
                        currentMediaId = c.currentMediaItem?.mediaId,
                        currentItemIndex = c.currentMediaItemIndex,
                        speed = c.playbackParameters.speed,
                    )
                    positionMs.value = c.currentPosition
                    connected.value = true
                    restartTickerIfNeeded()
                    flushPendingActions(c)
                    // Cold-start resume: if nothing is targeted and the service has no
                    // items either (fresh process, no headphone-button wake), prime the
                    // mini-player + theme context with the most-recently-played book so
                    // the user lands back where they left off. Actual MediaItems aren't
                    // loaded yet — first user transport (play / playChapter) lazy-calls
                    // [playBookFromPosition] to materialise them. Seeded position drives
                    // the scrubber + chapter highlight so the UI looks "warm" immediately.
                    if (targetedBookId.value == null && c.mediaItemCount == 0) {
                        applicationScope.launch { primeLastPlayedBook() }
                    }
                } catch (_: Throwable) {
                    // Connection failed. Schedule a single linear-backoff retry; Media3
                    // typically restarts the service on the first transport command anyway,
                    // so we don't burn through retries during a benign cold-start race.
                    scheduleReconnect()
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun scheduleReconnect() {
        if (released) return
        val attempt = reconnectAttempts + 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) return
        reconnectAttempts = attempt
        applicationScope.launch {
            delay(RECONNECT_BACKOFF_MS * attempt)
            if (!released && controller == null) bindController()
        }
    }

    private fun flushPendingActions(c: MediaController) {
        // Drain on Main — every queued action expects Main-thread invariants (the same as the
        // [onMain] path). The queue is FIFO so commands replay in the order the user issued
        // them. If a queued action fails (e.g. binder dies again mid-flush), we drop the
        // remainder; the controller listener will re-trigger a rebind.
        applicationScope.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                val action = pendingActions.poll() ?: return@launch
                try { action(c) } catch (_: Throwable) { return@launch }
            }
        }
    }


    // ---------------------------------------------------------------- NowPlayingState

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val state: StateFlow<PlaybackUiState> = combine(
        connected,
        targetedBookId,
    ) { isConnected, bookId -> isConnected to bookId }
        .flatMapLatest { (isConnected, bookId) ->
            when {
                !isConnected -> flowOf<PlaybackUiState>(PlaybackUiState.Loading)
                bookId == null -> flowOf<PlaybackUiState>(PlaybackUiState.Idle)
                else -> projectLoaded(bookId)
            }
        }
        .stateIn(
            applicationScope,
            SharingStarted.Eagerly,
            PlaybackUiState.Loading,
        )

    /**
     * Event-driven projection (R.C.5): book + chapter list emit on Room change, [playerSnapshot]
     * emits on listener events; only [positionMs] ticks at 250ms while playing. None of those
     * three sources re-fetches the book / chapter list — that's the whole point of the split.
     */
    private fun projectLoaded(bookId: String): kotlinx.coroutines.flow.Flow<PlaybackUiState> {
        val bookFlow = bookSource.observeBook(bookId)
        val chaptersFlow = chapterSource.observeChaptersForBook(bookId)
        return combine(
            bookFlow,
            chaptersFlow,
            playerSnapshot,
            positionMs,
        ) { book, chapters, snap, position ->
            if (book == null) {
                PlaybackUiState.BookNotFound
            } else {
                // SingleFile dispatch (matches startPlayback): when all chapters share one
                // fileUri, `c.currentPosition` is book-absolute and `currentMediaItemIndex`
                // is always 0. Derive the current chapter from absolute position instead.
                // MultiFile: trust `currentItemIndex` (one mediaItem per chapter file).
                val singleFileFirstUri = chapters.firstOrNull()?.fileUri
                val isSingleFile = singleFileFirstUri != null && chapters.all { it.fileUri == singleFileFirstUri }
                val currentChapter = if (isSingleFile) {
                    chapterAtPosition(chapters, position)
                } else {
                    chapterAt(chapters, snap.currentItemIndex)
                }
                PlaybackUiState.Loaded(
                    book = book,
                    currentChapter = currentChapter,
                    positionInBookMs = position,
                    isPlaying = snap.isPlaying,
                    speed = snap.speed,
                    skipSilenceEnabled = book.skipSilenceEnabled,
                    gainDb = book.gainDb,
                )
            }
        }
    }

    private fun chapterAt(chapters: List<ChapterEntity>, index: Int): ChapterEntity? {
        if (chapters.isEmpty()) return null
        return chapters.getOrNull(index.coerceIn(0, chapters.lastIndex))
    }

    /**
     * SingleFile chapter detection: find the chapter whose `[positionInBookMs, nextStart)`
     * range contains [positionInBookMs]. Falls back to the last chapter if position is past
     * the final start.
     */
    private fun chapterAtPosition(chapters: List<ChapterEntity>, positionInBookMs: Long): ChapterEntity? {
        if (chapters.isEmpty()) return null
        // chapters are ORDER BY chapterIndex → ascending positionInBookMs.
        for (i in chapters.indices.reversed()) {
            if (chapters[i].positionInBookMs <= positionInBookMs) return chapters[i]
        }
        return chapters.first()
    }

    // ---------------------------------------------------------------- ticker

    private var tickerJob: Job? = null

    private fun restartTickerIfNeeded() {
        tickerJob?.cancel()
        val c = controller ?: return
        if (!c.isPlaying) {
            // One last sync write so a pause/seek lands in UI immediately.
            positionMs.value = c.currentPosition
            return
        }
        tickerJob = applicationScope.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            // ~250ms ticks while playing (R.C.5). Reads only the position field.
            while (true) {
                val live = controller ?: return@launch
                if (!live.isPlaying) return@launch
                positionMs.value = live.currentPosition
                delay(TICKER_INTERVAL_MS)
            }
        }
    }

    // ---------------------------------------------------------------- TransportCommands

    override suspend fun play() {
        // Decision logic lives in [decidePlay] (pure, unit-tested); this is the thin
        // dispatcher. Reading `c.mediaItemCount` and `c.playbackState` requires Main, so
        // we sample them once via [onMain].
        var count = 0
        var playbackState = androidx.media3.common.Player.STATE_IDLE
        onMain { c ->
            count = c.mediaItemCount
            playbackState = c.playbackState
        }
        var decision = decidePlay(count, targetedBookId.value)
        if (decision == PlayAction.Idle) {
            // Race: user tapped play before primeLastPlayedBook finished resolving the
            // most-recently-played book on cold start. Prime synchronously and retry the
            // decision so the tap isn't lost.
            primeLastPlayedBook()
            decision = decidePlay(count, targetedBookId.value)
        }
        when (decision) {
            is PlayAction.BootBook -> playBook(decision.bookId)
            PlayAction.Idle -> android.util.Log.w(
                "whisperboy.playback",
                "play() Idle: no items, no targeted book, no recently-played candidate",
            )
            PlayAction.Resume -> {
                // STATE_IDLE means the controller has items but has not been prepared
                // (service was killed, restored controller, etc.). c.play() would no-op
                // — re-call playBook so prepare() + playWhenReady fire fresh.
                if (playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    val targeted = targetedBookId.value
                    if (targeted != null) {
                        playBook(targeted)
                        return
                    }
                }
                onMain { c ->
                    // F.4 — if the player has been paused for more than the auto-rewind threshold, nudge the
                    // playhead back by `autoRewindMs` before resuming. Coerces at 0 so books that paused near
                    // the start don't seek negative.
                    val pausedAt = pausedAtEpochMs
                    pausedAtEpochMs = null
                    if (pausedAt != null && !c.isPlaying) {
                        val idleMs = System.currentTimeMillis() - pausedAt
                        if (idleMs > AUTO_REWIND_THRESHOLD_MS && autoRewindMs > 0L) {
                            val target = (c.currentPosition - autoRewindMs).coerceAtLeast(0L)
                            c.seekTo(target)
                        }
                    }
                    c.play()
                }
            }
        }
    }
    override suspend fun pause() = onMain { it.pause() }

    override suspend fun seekTo(positionInBookMs: Long) = onMain { c ->
        // Phase F.1 ships chapter-scoped scrubber semantics: callers pass a book-absolute
        // position. We translate to (mediaItemIndex, positionInChapterMs) using the controller's
        // own media-item duration; sleep-timer / chapter-list seeking will use the same path.
        val chapterCount = c.mediaItemCount
        if (chapterCount == 0) {
            c.seekTo(positionInBookMs.coerceAtLeast(0L))
            return@onMain
        }
        // Walk forward summing item durations until we land in the bucket. C.TIME_UNSET means
        // "duration not known yet" — fall back to seeking within the current item.
        var remaining = positionInBookMs.coerceAtLeast(0L)
        var idx = 0
        while (idx < chapterCount - 1) {
            val duration = c.getMediaItemAt(idx).let { _ ->
                // The controller exposes per-item duration only for the *current* item via
                // `duration`. For other items we rely on the MediaItem's `clippingConfiguration`
                // / `mediaMetadata.durationMs` if set by the session.
                c.currentMediaItem?.takeIf { c.currentMediaItemIndex == idx }
                    ?.let { c.duration.takeIf { d -> d != androidx.media3.common.C.TIME_UNSET } }
                    ?: c.getMediaItemAt(idx).mediaMetadata.durationMs
                    ?: -1L
            }
            if (duration <= 0 || remaining < duration) break
            remaining -= duration
            idx += 1
        }
        c.seekTo(idx, remaining)
        positionMs.value = positionInBookMs
    }

    override suspend fun playChapter(chapterIndex: Int, positionInBookMs: Long) {
        // Pure decision in [decideChapterTap]; this is the thin dispatcher.
        var count = 0
        onMain { c -> count = c.mediaItemCount }
        when (val decision = decideChapterTap(count, targetedBookId.value, chapterIndex, positionInBookMs)) {
            is ChapterTapAction.BootBookFromPosition ->
                playBookFromPosition(decision.bookId, decision.positionInBookMs)
            is ChapterTapAction.SeekSingleFile -> onMain { c ->
                c.seekTo(0, decision.positionInBookMs)
                positionMs.value = decision.positionInBookMs
                if (!c.isPlaying) c.play()
            }
            is ChapterTapAction.SeekMultiFile -> onMain { c ->
                c.seekTo(decision.index, 0L)
                positionMs.value = 0L
                // Read mediaId via `getMediaItemAt(index)` rather than
                // `c.currentMediaItem` — the latter races with the async
                // item-transition and can still report the old chapter.
                playerSnapshot.update {
                    it.copy(
                        currentItemIndex = decision.index,
                        currentMediaId = c.getMediaItemAt(decision.index).mediaId,
                    )
                }
                if (!c.isPlaying) c.play()
            }
            ChapterTapAction.Idle -> Unit
        }
    }

    override suspend fun rewind() = onMain { c ->
        val target = (c.currentPosition - rewindMs).coerceAtLeast(0L)
        c.seekTo(target)
    }

    override suspend fun forward() = onMain { c ->
        val target = c.currentPosition + forwardMs
        c.seekTo(target)
    }

    override suspend fun nextChapter() = onMain { c ->
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
    }

    override suspend fun prevChapter() = onMain { c ->
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0L)
    }

    override suspend fun setSpeed(speed: Float) {
        val coerced = speed.coerceIn(0.5f, 3.5f)
        onMain { c -> c.setPlaybackSpeed(coerced) }
        // Persist per-book so the value sticks across app restarts AND is reapplied on the next
        // [playBook] (Voice's per-book settings pattern; see Phase J.4).
        targetedBookId.value?.let { id ->
            applicationScope.launch { bookSource.setSpeed(id, coerced) }
        }
    }

    override suspend fun setSkipSilence(enabled: Boolean) {
        // Skip-silence is an ExoPlayer-only knob — [MediaController] doesn't expose it across the
        // IPC boundary. Send a custom session command (Phase J) that the session callback
        // translates into `ExoPlayer.skipSilenceEnabled` on the service side.
        sendCustomCommand(
            CustomCommands.SET_SKIP_SILENCE,
            android.os.Bundle().apply {
                putBoolean(CustomCommands.EXTRA_ENABLED, enabled)
            },
        )
        playerSnapshot.update { it.copy(skipSilenceRequested = enabled) }
        targetedBookId.value?.let { id ->
            applicationScope.launch { bookSource.setSkipSilence(id, enabled) }
        }
    }

    override suspend fun setGain(gainDb: Float) {
        val coerced = gainDb.coerceIn(-3f, 12f)
        sendCustomCommand(
            CustomCommands.SET_GAIN_DB,
            android.os.Bundle().apply {
                putFloat(CustomCommands.EXTRA_GAIN_DB, coerced)
            },
        )
        playerSnapshot.update { it.copy(gainDbRequested = coerced) }
        targetedBookId.value?.let { id ->
            applicationScope.launch { bookSource.setGain(id, coerced) }
        }
    }

    /**
     * Phase J helper — send a custom [androidx.media3.session.SessionCommand] on the Main thread.
     * No-ops cleanly when the controller hasn't connected yet (UI may invoke before
     * `buildAsync` resolves). The returned `ListenableFuture` is intentionally ignored —
     * callers don't care about per-command ack, just that the latest write wins.
     */
    private suspend fun sendCustomCommand(action: String, args: android.os.Bundle) {
        val c = controller ?: return
        withContext(Dispatchers.Main) {
            c.sendCustomCommand(
                androidx.media3.session.SessionCommand(action, android.os.Bundle.EMPTY),
                args,
            )
        }
    }

    // ---------------------------------------------------------------- BookCommands

    override suspend fun playBook(bookId: String) {
        val book = bookSource.observeBook(bookId).firstOrLoadingDefault() ?: return
        val chapters = chapterSource.chaptersFor(bookId)
        startPlayback(book, chapters, startMs = book.positionInChapterMs, startIndex = book.currentChapterIndex)
    }

    override suspend fun playBookFromPosition(bookId: String, positionInBookMs: Long) {
        val book = bookSource.observeBook(bookId).firstOrLoadingDefault() ?: return
        val chapters = chapterSource.chaptersFor(bookId)
        // Resolve the book-absolute offset into (chapterIndex, positionInChapterMs).
        var remaining = positionInBookMs.coerceAtLeast(0L)
        var idx = 0
        for ((i, ch) in chapters.withIndex()) {
            if (remaining < ch.durationMs || i == chapters.lastIndex) {
                idx = i
                break
            }
            remaining -= ch.durationMs
        }
        startPlayback(book, chapters, startMs = remaining, startIndex = idx)
    }

    /**
     * Cold-start resume helper. Looks up the most-recently-played active book and seeds
     * [targetedBookId] + [positionMs] + [playerSnapshot] so the mini-player, theme, and
     * player screen all render the book the user was last listening to — without touching
     * the MediaController (no items loaded, no audio output). The first transport command
     * (`play` / `playChapter`) sees `mediaItemCount == 0` and lazy-materialises items via
     * [playBookFromPosition].
     */
    private suspend fun primeLastPlayedBook() {
        val candidate = bookSource.observeBooks().firstOrNull()
            ?.filter { it.active && it.lastPlayedAt != null }
            ?.maxByOrNull { it.lastPlayedAt ?: 0L }
            ?: return
        if (targetedBookId.value != null) return
        val chapters = chapterSource.chaptersFor(candidate.bookId)
        if (chapters.isEmpty()) return
        val chapterStart = chapters
            .getOrNull(candidate.currentChapterIndex.coerceIn(0, chapters.lastIndex))
            ?.positionInBookMs ?: 0L
        positionMs.value = (chapterStart + candidate.positionInChapterMs).coerceAtLeast(0L)
        playerSnapshot.update {
            it.copy(currentItemIndex = candidate.currentChapterIndex.coerceAtLeast(0))
        }
        targetedBookId.value = candidate.bookId
    }

    private suspend fun startPlayback(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        startMs: Long,
        startIndex: Int,
    ) = onMain { c ->
        targetedBookId.value = book.bookId
        android.util.Log.d(
            "whisperboy.playback",
            "startPlayback book=${book.bookId} chapters=${chapters.size} firstUri=${chapters.firstOrNull()?.fileUri} startIdx=$startIndex startMs=$startMs",
        )
        // SingleFile vs MultiFile dispatch:
        //
        // SingleFile (all chapters share one fileUri, e.g. an .m4b with embedded chapter marks):
        // build ONE MediaItem for the whole file. `c.currentPosition` is then book-absolute,
        // `c.mediaItemCount = 1`, and chapter navigation is position-seek-within-the-item.
        // Chapter detection happens UI-side from absolute position vs `chapter.positionInBookMs`.
        // This mirrors Voice's approach and avoids the ClippingMediaSource quirks (no
        // auto-advance, clip-relative positions confusing the scrubber).
        //
        // MultiFile (one fileUri per chapter, the typical folder-of-MP3s case): one MediaItem
        // per chapter file. `c.currentPosition` is within-file; `c.mediaItemCount = N`;
        // chapter navigation is `seekTo(chapterIndex, 0)`.
        val firstUri = chapters.firstOrNull()?.fileUri
        val isSingleFile = firstUri != null && chapters.all { it.fileUri == firstUri }
        val items = if (isSingleFile) {
            listOf(
                MediaItem.Builder()
                    .setMediaId(book.bookId)
                    .setUri(firstUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(book.title)
                            .setArtist(book.author)
                            .setAlbumTitle(book.title)
                            .setDurationMs(book.durationMs)
                            .build(),
                    )
                    .build()
            )
        } else {
            chapters.map { ch ->
                val uri = ch.fileUri ?: book.relativePath
                MediaItem.Builder()
                    .setMediaId(ch.chapterId)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(ch.title ?: book.title)
                            .setArtist(book.author)
                            .setAlbumTitle(book.title)
                            .setDurationMs(ch.durationMs)
                            .build(),
                    )
                    .build()
            }
        }
        if (items.isEmpty()) {
            c.setMediaItems(emptyList())
        } else if (isSingleFile) {
            // Resume position: convert (startIndex, startMs) → book-absolute for the single
            // media item. startMs in single-file mode came in as "within saved chapter" so we
            // add the chapter's offset to get absolute file position.
            val absolute = (chapters.getOrNull(startIndex)?.positionInBookMs ?: 0L) + startMs
            c.setMediaItems(items, 0, absolute.coerceAtLeast(0L))
        } else {
            c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startMs.coerceAtLeast(0L))
        }
        c.prepare()
        // Phase J — apply persisted per-book knobs as playback starts so the user's last
        // speed / skip-silence / gain settings stick across sessions. Speed goes straight to
        // the controller; skip-silence + gain travel via custom session commands because
        // [MediaController] doesn't surface them.
        c.setPlaybackSpeed(book.speed.coerceIn(0.5f, 3.5f))
        c.sendCustomCommand(
            androidx.media3.session.SessionCommand(CustomCommands.SET_SKIP_SILENCE, android.os.Bundle.EMPTY),
            android.os.Bundle().apply {
                putBoolean(CustomCommands.EXTRA_ENABLED, book.skipSilenceEnabled)
            },
        )
        c.sendCustomCommand(
            androidx.media3.session.SessionCommand(CustomCommands.SET_GAIN_DB, android.os.Bundle.EMPTY),
            android.os.Bundle().apply {
                putFloat(CustomCommands.EXTRA_GAIN_DB, book.gainDb.coerceIn(-3f, 12f))
            },
        )
        c.playWhenReady = true
    }

    // ---------------------------------------------------------------- helpers

    private suspend inline fun onMain(crossinline block: (MediaController) -> Unit) {
        val c = controller
        if (c == null) {
            // Phase R — service died (or never connected yet). Queue the action so it replays
            // once [bindController] finishes, and trigger a rebind in case the listener
            // [controllerListener.onDisconnected] hasn't fired yet (some Media3 paths drop the
            // binder without an explicit disconnect callback on aggressive system kills).
            if (pendingActions.size < MAX_PENDING_ACTIONS) {
                pendingActions.add { live -> block(live) }
            }
            if (!released) bindController()
            return
        }
        withContext(Dispatchers.Main) { block(c) }
    }

    private suspend fun kotlinx.coroutines.flow.Flow<BookEntity?>.firstOrLoadingDefault(): BookEntity? {
        // Tiny helper: grab the first emission. Phase F doesn't need a hard timeout because the
        // BookEntity is already cached in Room from the last scan; if it really isn't there, the
        // caller's `playBook` is a user error and the null return surfaces as `BookNotFound` via
        // the state projection.
        return this.firstOrNull()
    }

    // ---------------------------------------------------------------- PlayerHandle (Phase G)

    override suspend fun setVolumeNow(volume: Float) = onMain { c ->
        c.volume = volume.coerceIn(0f, 1f)
    }

    override suspend fun pauseNow() = onMain { it.pause() }

    override suspend fun currentPositionMs(): Long {
        var pos = 0L
        onMain { c -> pos = c.currentPosition }
        return pos
    }

    override suspend fun currentBookId(): String? = targetedBookId.value

    override suspend fun currentChapterId(): String? {
        var id: String? = null
        onMain { c -> id = c.currentMediaItem?.mediaId }
        return id
    }

    /**
     * Phase P.7 — read the current `(bookId, chapterIndex, positionInChapterMs)` triple off the
     * live controller and persist it via [BookSource.updatePosition]. Called from:
     *
     *  - [playerListener.onMediaItemTransition] — chapter boundary
     *  - [playerListener.onIsPlayingChanged] when `isPlaying == false` — pause
     *  - [processLifecycleObserver.onStop] — app went to background
     *  - [com.eight87.whisperboy.AppGraph.flushPlaybackPosition] — called by
     *    [PlaybackService.onDestroy] before service teardown
     *
     * No-op when there's no targeted book or the controller hasn't connected yet. Read of
     * [MediaController.currentPosition] / `currentMediaItemIndex` is done synchronously on
     * the caller's thread; both fields are safe to read from any thread per Media3's
     * [MediaController] contract (they internally marshal to the player thread).
     *
     * `lastPlayedAt` is wall-clock `System.currentTimeMillis()` because that's what the UI
     * sorts on ("Recently played") and it's also what the existing `playBook` path bumps via
     * downstream listener events. Position math uses the controller's in-chapter position,
     * NOT a book-absolute value — book-absolute is a UI projection (see [projectLoaded]);
     * the BookEntity row stores `(currentChapterIndex, positionInChapterMs)`.
     */
    fun saveCurrentPosition() {
        val bookId = targetedBookId.value ?: return
        val c = controller ?: return
        // PRECONDITION: caller is on Main. The Media3 [MediaController] enforces this for
        // `currentMediaItemIndex` / `currentPosition` with a runtime check that throws
        // `IllegalStateException("MediaController method is called from a wrong thread")`.
        // All listener callbacks ([Player.Listener.onIsPlayingChanged],
        // [Player.Listener.onMediaItemTransition]) and lifecycle callbacks
        // ([DefaultLifecycleObserver.onStop], [release]) fire on Main, so direct callers
        // here are fine. Coroutine collectors on the application scope are NOT — those must
        // route through [saveCurrentPositionFromMain] via [onMain] instead.
        saveCurrentPositionFromMain(c)
    }

    /**
     * Crash-fix helper — the body of [saveCurrentPosition] when the caller has already hopped
     * to Main and produced the live [MediaController]. Used by the 1Hz `sample` collector in
     * [init], which runs on the application scope's default dispatcher and would otherwise
     * crash on `currentMediaItemIndex` / `currentPosition`. The Room write is fanned back out
     * onto the application scope so the Main thread doesn't block on the IO dispatcher.
     */
    private fun saveCurrentPositionFromMain(c: MediaController) {
        val bookId = targetedBookId.value ?: return
        val chapterIndex = c.currentMediaItemIndex
        val positionMs = c.currentPosition.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        applicationScope.launch {
            bookSource.updatePosition(bookId, chapterIndex, positionMs, now)
        }
    }

    fun release() {
        // Phase P.7 — make sure the latest position is flushed before we tear down.
        saveCurrentPosition()
        released = true
        pendingActions.clear()
        processLifecycle.removeObserver(processLifecycleObserver)
        tickerJob?.cancel()
        controller?.release()
        controller = null
        connected.value = false
    }

    /** Internal snapshot of fields driven by [Player.Listener] (NOT the position ticker). */
    private data class PlayerSnapshot(
        val isPlaying: Boolean = false,
        val currentMediaId: String? = null,
        val currentItemIndex: Int = 0,
        val speed: Float = 1.0f,
        val skipSilenceRequested: Boolean = false,
        val gainDbRequested: Float = 0.0f,
    )

    private companion object {
        const val TICKER_INTERVAL_MS = 250L

        /** Phase P.7 — 1Hz throttled position-save while playing (sampled off the ticker). */
        const val POSITION_SAVE_INTERVAL_MS = 1_000L

        /** Phase R — cap on queued transport actions while disconnected. */
        const val MAX_PENDING_ACTIONS = 16

        /** Phase R — max bind retries on initial-connect or post-disconnect failure. */
        const val MAX_RECONNECT_ATTEMPTS = 3

        /** Phase R — linear backoff base (multiplied by attempt #) between bind retries. */
        const val RECONNECT_BACKOFF_MS = 500L

        /** Default rewind / forward seek length (F.3 fallback before the settings collector emits). */
        const val DEFAULT_SEEK_MS = 30_000L

        /** Default auto-rewind length on long-pause resume (F.4 fallback). */
        const val DEFAULT_AUTO_REWIND_MS = 5_000L

        /** Auto-rewind triggers when the pause→resume gap exceeds this. Voice uses 5min. */
        const val AUTO_REWIND_THRESHOLD_MS = 5 * 60 * 1000L
    }
}
