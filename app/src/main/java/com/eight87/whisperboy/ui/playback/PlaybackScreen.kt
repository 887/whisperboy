package com.eight87.whisperboy.ui.playback

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.BookmarkSource
import com.eight87.whisperboy.data.library.ChapterEntity
import com.eight87.whisperboy.data.library.ChapterSource
import com.eight87.whisperboy.data.playback.PlaybackSettings
import com.eight87.whisperboy.playback.NowPlayingState
import com.eight87.whisperboy.playback.PlaybackUiState
import com.eight87.whisperboy.playback.SleepTimerCommands
import com.eight87.whisperboy.playback.SleepTimerState
import com.eight87.whisperboy.playback.TransportCommands
import com.eight87.whisperboy.theme.PlaybackAccent
import com.eight87.whisperboy.ui.common.CoverArt
import kotlinx.coroutines.launch

/**
 * Phase F.1 — full-screen player.
 *
 * Takes only the narrow [NowPlayingState] + [TransportCommands] interfaces (R.A discipline) — no
 * import of `BookSource`, no import of `PlaybackController`. Cold-start-perf discipline: state
 * collected via `collectAsStateWithLifecycle` (B.1); no `BoxWithConstraints` for sizing (B.3);
 * scrubber slider's value-change handler is direct (no `Modifier.offset { IntOffset(...) }`
 * needed since `Slider` already handles position via its own state).
 *
 * Five view branches: Idle (no session — unusual; route always lands with a bookId) shows a
 * spinner; Loading (controller connecting OR book row not yet emitted) shows a spinner;
 * BookNotFound shows an error message + back; Loaded renders the player chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    state: NowPlayingState,
    transport: TransportCommands,
    chapterSource: ChapterSource,
    bookmarkSource: BookmarkSource,
    playbackSettings: PlaybackSettings,
    sleepTimerCommands: SleepTimerCommands,
    onBack: () -> Unit,
    onViewBookmarksClick: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Optional list state for the inline chapter-queue LazyColumn so a host
     * that wants to drive overscroll-down → sheet-collapse can wire a
     * `NestedScrollConnection` against it. When non-null, the queue uses
     * this hoisted state; otherwise it allocates its own local one.
     */
    chapterListState: LazyListState? = null,
) {
    val uiState by state.state.collectAsStateWithLifecycle()
    val sleepState by sleepTimerCommands.state.collectAsStateWithLifecycle()
    var sleepSheetOpen by remember { mutableStateOf(false) }
    // Phase H.2 — add-bookmark dialog visibility. Lives at this scope (not inside
    // PlayerLoaded) so the IconButton in the TopAppBar can flip it without having
    // to thread a callback through the loaded branch.
    var addBookmarkDialogOpen by remember { mutableStateOf(false) }
    // Phase J — per-book playback-options sheet (speed / skip silence / gain).
    var optionsSheetOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // F.6 — player-screen vertical gradient. Reads the album palette
    // from [LocalAlbumPalette] (published by [WhisperboyTheme] off
    // the currently-playing book's cover) — single source of truth,
    // same tint that's already blending the rest of the app chrome.
    // User-picked custom tint still wins; album-art toggle still gates.
    val customTint = com.eight87.whisperboy.theme.LocalCustomChromeTint.current
    val tintByAlbumArt = com.eight87.whisperboy.theme.LocalTintByAlbumArt.current
    val albumTint = com.eight87.whisperboy.theme.LocalAlbumPalette.current.surfaceTint
    val surface = MaterialTheme.colorScheme.surface
    val effectiveTint = customTint ?: if (tintByAlbumArt) albumTint else null
    // Animate the top-of-gradient color so book changes cross-fade instead of snap.
    // Single per-screen animation (not a 9-call theme crossfade — see cold-start-perf B.2);
    // the alpha multiply is folded into the target so we don't allocate a second `Color`.
    val animatedTop by animateColorAsState(
        targetValue = (effectiveTint ?: surface).copy(alpha = 0.4f),
        label = "playerTintTop",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(animatedTop, surface),
                ),
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = playerTitle(uiState)) },
            // Transparent container so the F.6 Palette-tinted gradient painted on the
            // wrapping Box shows through the app-bar band at the top of the screen.
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_back_cd),
                    )
                }
            },
            actions = {
                SleepTimerButton(
                    sleepState = sleepState,
                    onClick = { sleepSheetOpen = true },
                )
                // Phase H.2 — add-bookmark. Disabled when not Loaded (no book to attach to).
                val loaded = uiState as? PlaybackUiState.Loaded
                IconButton(
                    onClick = { addBookmarkDialogOpen = true },
                    enabled = loaded != null,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(R.string.player_bookmark_cd),
                    )
                }
                // Phase H.1 — view-bookmarks icon. Uses Icons.Filled.Bookmarks (plural)
                // to distinguish "open the list" from the singular Bookmark "add one".
                // Disabled until a Loaded book is known.
                IconButton(
                    onClick = { loaded?.let { onViewBookmarksClick(it.book.bookId) } },
                    enabled = loaded != null,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bookmarks,
                        contentDescription = stringResource(R.string.bookmark_screen_view_cd),
                    )
                }
                // Phase J — overflow IconButton opens the per-book playback options sheet
                // (speed / skip silence / volume gain). Disabled until a book is loaded
                // since all three knobs live on the book row.
                IconButton(
                    onClick = { optionsSheetOpen = true },
                    enabled = loaded != null,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.player_overflow_cd),
                    )
                }
            },
        )

        when (val s = uiState) {
            PlaybackUiState.Idle,
            PlaybackUiState.Loading -> PlayerLoading(modifier = Modifier.weight(1f).fillMaxWidth())
            PlaybackUiState.BookNotFound -> PlayerBookNotFound(modifier = Modifier.weight(1f).fillMaxWidth())
            is PlaybackUiState.Loaded -> PlayerLoaded(
                state = s,
                transport = transport,
                playbackSettings = playbackSettings,
                chapterSource = chapterSource,
                chapterListState = chapterListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        }
    }

    if (sleepSheetOpen) {
        SleepTimerSheet(
            sleepTimerCommands = sleepTimerCommands,
            onDismiss = { sleepSheetOpen = false },
        )
    }

    // Phase H.2 — add-bookmark dialog. Only meaningful in the Loaded branch (we need a
    // bookId + chapterId + positionInBookMs to write the row); guarded by the same
    // `loaded != null` predicate the icon button uses, so the dialog can't open without
    // a book.
    val loadedForDialog = uiState as? PlaybackUiState.Loaded
    if (addBookmarkDialogOpen && loadedForDialog != null) {
        val chapter = loadedForDialog.currentChapter
        val positionInChapter = positionInChapter(loadedForDialog)
        val chapterTitle = chapter?.title
            ?: chapter?.let { stringResource(R.string.player_unknown_chapter, it.chapterIndex + 1) }
            ?: loadedForDialog.book.title
        val defaultTitle = stringResource(
            R.string.bookmark_add_default_title_format,
            chapterTitle,
            formatMmSs(positionInChapter),
        )
        AddBookmarkDialog(
            defaultTitle = defaultTitle,
            onConfirm = { title ->
                scope.launch {
                    bookmarkSource.addBookmark(
                        bookId = loadedForDialog.book.bookId,
                        chapterId = chapter?.chapterId,
                        title = title.ifBlank { null },
                        positionInBookMs = loadedForDialog.positionInBookMs,
                        setBySleepTimer = false,
                    )
                }
                addBookmarkDialogOpen = false
            },
            onDismiss = { addBookmarkDialogOpen = false },
        )
    }

    // Phase J — per-book playback-options sheet. Values come straight off [Loaded] (which is
    // sourced from [BookEntity]), so the sheet renders the user's last persisted choices.
    val loadedForOptions = uiState as? PlaybackUiState.Loaded
    if (optionsSheetOpen && loadedForOptions != null) {
        PlaybackOptionsSheet(
            speed = loadedForOptions.speed,
            skipSilenceEnabled = loadedForOptions.skipSilenceEnabled,
            gainDb = loadedForOptions.gainDb,
            onSpeedChange = { value -> scope.launch { transport.setSpeed(value) } },
            onSkipSilenceChange = { value -> scope.launch { transport.setSkipSilence(value) } },
            onGainChange = { value -> scope.launch { transport.setGain(value) } },
            onDismiss = { optionsSheetOpen = false },
        )
    }
}

/**
 * Phase J — per-book playback options sheet. Three sections:
 *  - Playback speed: 0.5..3.5x in 0.05 steps, numeric display, reset button.
 *  - Skip silent passages: Switch.
 *  - Volume gain: -3..+12 dB in 0.5 dB steps, numeric +/- dB readout.
 *
 * Each `onChange` is fire-and-forget from the screen's scope; persistence + ExoPlayer apply
 * happens inside [PlaybackController].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackOptionsSheet(
    speed: Float,
    skipSilenceEnabled: Boolean,
    gainDb: Float,
    onSpeedChange: (Float) -> Unit,
    onSkipSilenceChange: (Boolean) -> Unit,
    onGainChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // m3-expressive D.3 — playback-options sheet on the M3E `surfaceContainer`
    // tier with the playback-category accent (orange) tinting the drag handle.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(color = PlaybackAccent.onContainer) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // --- Speed ---
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.player_speed_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.player_speed_value_format, speed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = speed.coerceIn(0.5f, 3.5f),
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..3.5f,
                    // (3.5 - 0.5) / 0.05 = 60 internal positions, minus the 2 endpoints = 59 steps.
                    steps = 59,
                )
                TextButton(
                    onClick = { onSpeedChange(1.0f) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(text = stringResource(R.string.player_speed_reset))
                }
            }

            // --- Skip silence ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.player_silence_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.player_silence_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = skipSilenceEnabled,
                    onCheckedChange = onSkipSilenceChange,
                )
            }

            // --- Volume gain ---
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.player_gain_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.player_gain_value_format, gainDb),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = gainDb.coerceIn(-3f, 12f),
                    onValueChange = onGainChange,
                    valueRange = -3f..12f,
                    // (12 - -3) / 0.5 = 30 internal positions, minus the 2 endpoints = 29 steps.
                    steps = 29,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Phase H.2 — inline add-bookmark dialog launched from the player's top-app-bar bookmark
 * button. Pre-fills the title with `<chapter title> — <mm:ss in chapter>`; the user can rename
 * or accept. Save fires [onConfirm] with the (possibly edited) title; a blank title is treated
 * as null so the bookmark list falls back to its default-title rendering.
 */
@Composable
private fun AddBookmarkDialog(
    defaultTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(defaultTitle) { mutableStateOf(defaultTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.bookmark_add_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.bookmark_add_dialog_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.bookmark_add_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/**
 * Phase G.2 — top-app-bar sleep timer button. Inactive: plain timer icon. Running with a known
 * remaining time: shows "mm:ss" as the button label. End-of-chapter mode (remainingMs = -1):
 * shows the icon + small "EoC" dot. PausedAwaitingShake: shows a shake hint.
 *
 * Renders inside the parent [TopAppBar]'s `actions` slot, so the touch target is sized by
 * [IconButton]; we render a `Box` overlay over the icon for the text badge.
 */
@Composable
private fun SleepTimerButton(
    sleepState: SleepTimerState,
    onClick: () -> Unit,
) {
    val cd = when (sleepState) {
        SleepTimerState.Inactive -> stringResource(R.string.sleep_timer_cd_inactive)
        is SleepTimerState.Running ->
            if (sleepState.remainingMs > 0L) {
                stringResource(R.string.sleep_timer_cd_running, (sleepState.remainingMs / 60_000L).toInt())
            } else {
                stringResource(R.string.sleep_end_of_chapter)
            }
        is SleepTimerState.PausedAwaitingShake -> stringResource(R.string.sleep_shake_to_resume_window)
    }
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Timer,
                contentDescription = cd,
            )
            val badge = when (sleepState) {
                is SleepTimerState.Running ->
                    if (sleepState.remainingMs > 0L) formatMmSs(sleepState.remainingMs) else null
                else -> null
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.player_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerBookNotFound(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.player_book_not_found_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.player_book_not_found_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayerLoaded(
    state: PlaybackUiState.Loaded,
    transport: TransportCommands,
    playbackSettings: PlaybackSettings,
    chapterSource: ChapterSource,
    chapterListState: LazyListState?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val chapterDurationMs = state.currentChapter?.durationMs ?: state.book.durationMs
    // F.3 — current user-configured seek defaults. Cold-start-perf B.1: collectAsStateWithLifecycle
    // defers collection to `Lifecycle.STARTED`, fallback `30` matches `PlaybackSettings`' default
    // so the picker buttons don't flash a wrong icon CD on first composition.
    val rewindSeconds by playbackSettings.rewindSeconds.collectAsStateWithLifecycle(initialValue = 30)
    val forwardSeconds by playbackSettings.forwardSeconds.collectAsStateWithLifecycle(initialValue = 30)

    // Unified-scroll player: cover / title / scrubber / transport are the first item in
    // ONE LazyColumn; chapter rows are `items(...)` after them. Header scrolls off as the
    // user pulls up — same shape tonearmboy uses for now-playing + queue. (Pinned-header
    // was the earlier shape; user explicitly asked for unified scroll like tonearmboy.)
    val chaptersFlow = remember(chapterSource, state.book.bookId) {
        chapterSource.observeChaptersForBook(state.book.bookId)
    }
    val chapters by chaptersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentChapterIndex = state.currentChapter?.chapterIndex ?: -1
    val fallbackListState = rememberLazyListState()
    val listState = chapterListState ?: fallbackListState

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "player_header", contentType = "header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                CoverArt(
                    coverPath = state.book.coverPath,
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .aspectRatio(1f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.book.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = chapterDisplayTitle(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                PlayerScrubber(
                    positionInChapterMs = positionInChapter(state),
                    chapterDurationMs = chapterDurationMs,
                    onSeek = { newPositionInChapterMs ->
                        val absolute = (state.currentChapter?.positionInBookMs ?: 0L) + newPositionInChapterMs
                        scope.launch { transport.seekTo(absolute) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                PlayerTransport(
                    isPlaying = state.isPlaying,
                    rewindSeconds = rewindSeconds,
                    forwardSeconds = forwardSeconds,
                    onPlayPause = {
                        scope.launch { if (state.isPlaying) transport.pause() else transport.play() }
                    },
                    onRewind = { scope.launch { transport.rewind() } },
                    onForward = { scope.launch { transport.forward() } },
                    onPrev = { scope.launch { transport.prevChapter() } },
                    onNext = { scope.launch { transport.nextChapter() } },
                    onSetRewindSeconds = { value ->
                        scope.launch { playbackSettings.setRewindSeconds(value) }
                    },
                    onSetForwardSeconds = { value ->
                        scope.launch { playbackSettings.setForwardSeconds(value) }
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        items(
            items = chapters,
            key = { it.chapterId },
            contentType = { "chapter" },
        ) { chapter ->
            ChapterQueueRow(
                chapter = chapter,
                isActive = chapter.chapterIndex == currentChapterIndex,
                onClick = {
                    scope.launch { transport.playChapter(chapter.chapterIndex, chapter.positionInBookMs) }
                },
            )
        }
    }
}

/**
 * Inline chapter queue. Replaces F.5's `ChapterListSheet` (a `ModalBottomSheet`) — same
 * data flow, same active-row treatment, but rendered directly in the player body so the
 * user doesn't have to open a sheet to see / tap chapters. Tonearmboy `NowPlayingScreen`
 * uses the same shape for its track queue.
 *
 * Lazy-mount discipline still holds: the Flow is `remember`-pinned to (chapterSource, bookId)
 * and collected via `collectAsStateWithLifecycle`, so the queue list isn't materialised until
 * Room emits. The whole composable only renders inside `PlayerLoaded`, so a not-yet-loaded
 * player branch never composes it.
 *
 * Stable keys on `items(...)` use `chapter.chapterId` (cold-start-perf E.1 — never use the
 * index as the LazyColumn key).
 */
@Composable
internal fun ChapterQueue(
    bookId: String,
    currentChapterIndex: Int,
    chapterSource: ChapterSource,
    listState: LazyListState?,
    onChapterTap: (chapterIndex: Int, positionInBookMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chaptersFlow = remember(chapterSource, bookId) {
        chapterSource.observeChaptersForBook(bookId)
    }
    val chapters by chaptersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fallbackListState = rememberLazyListState()
    val state = listState ?: fallbackListState
    // No auto-scroll. User's viewport is theirs from mount onward; tapping a chapter
    // plays it without yanking the list. Natural chapter advancement also leaves scroll
    // alone — the active row is highlighted via the row's `isActive` style.
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            items = chapters,
            key = { it.chapterId },
            contentType = { "chapter" },
        ) { chapter ->
            ChapterQueueRow(
                chapter = chapter,
                isActive = chapter.chapterIndex == currentChapterIndex,
                onClick = { onChapterTap(chapter.chapterIndex, chapter.positionInBookMs) },
            )
        }
    }
}

@Composable
private fun ChapterQueueRow(
    chapter: ChapterEntity,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // m3-expressive D.2 — elevate chapter rows onto M3E card surfaces. Active
    // row carries the full `secondaryContainer` token (matches Settings'
    // selected-row treatment); inactive rows ride a translucent
    // `surfaceContainerHigh` so the F.6 Palette-tinted gradient on the
    // parent Box still bleeds through, giving the queue a glassy-card look
    // against the book's cover tint rather than flat squares.
    val bg = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
    }
    val fg = if (isActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subFg = if (isActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .testTag(if (isActive) "chapter_row_active" else "chapter_row")
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = fg,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = chapter.title
                ?: stringResource(R.string.player_unknown_chapter, chapter.chapterIndex + 1),
            style = MaterialTheme.typography.bodyLarge,
            color = fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatMmSs(chapter.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = subFg,
        )
    }
}

@Composable
internal fun PlayerScrubber(
    positionInChapterMs: Long,
    chapterDurationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val safeDuration = chapterDurationMs.coerceAtLeast(1L)
    val safePosition = positionInChapterMs.coerceIn(0L, safeDuration)
    Slider(
        value = safePosition.toFloat(),
        onValueChange = { onSeek(it.toLong()) },
        valueRange = 0f..safeDuration.toFloat(),
        enabled = chapterDurationMs > 0L,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player_scrubber_slider"),
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatMmSs(safePosition),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatMmSs(safeDuration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerTransport(
    isPlaying: Boolean,
    rewindSeconds: Int,
    forwardSeconds: Int,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSetRewindSeconds: (Int) -> Unit,
    onSetForwardSeconds: (Int) -> Unit,
) {
    var rewindPickerOpen by remember { mutableStateOf(false) }
    var forwardPickerOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.player_skip_prev_cd),
                modifier = Modifier.size(36.dp),
            )
        }
        // Wrap the rewind icon in a combinedClickable Box so long-press opens the picker.
        // IconButton itself doesn't take onLongClick, so we hand-roll the affordance — same
        // visual shape (48dp touch target), same icon, just a richer gesture set.
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    onClick = onRewind,
                    onLongClick = { rewindPickerOpen = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.FastRewind,
                contentDescription = stringResource(R.string.player_rewind_cd, rewindSeconds),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.player_pause_cd else R.string.player_play_cd,
                ),
                modifier = Modifier.size(56.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    onClick = onForward,
                    onLongClick = { forwardPickerOpen = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.FastForward,
                contentDescription = stringResource(R.string.player_forward_cd, forwardSeconds),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.player_skip_next_cd),
                modifier = Modifier.size(36.dp),
            )
        }
    }

    if (rewindPickerOpen) {
        SeekSecondsPickerDialog(
            titleRes = R.string.player_rewind_picker_title,
            current = rewindSeconds,
            onPick = { value ->
                onSetRewindSeconds(value)
                rewindPickerOpen = false
            },
            onDismiss = { rewindPickerOpen = false },
        )
    }
    if (forwardPickerOpen) {
        SeekSecondsPickerDialog(
            titleRes = R.string.player_forward_picker_title,
            current = forwardSeconds,
            onPick = { value ->
                onSetForwardSeconds(value)
                forwardPickerOpen = false
            },
            onDismiss = { forwardPickerOpen = false },
        )
    }
}

/**
 * Quick-pick dialog for rewind / forward seconds. Four hardcoded values (5 / 10 / 30 / 60) match
 * `PlaybackSettings.ALLOWED_VALUES`; the current selection is highlighted via the button's
 * label being prefixed with a leading bullet so a screen-reader user knows which one is set.
 */
@Composable
private fun SeekSecondsPickerDialog(
    titleRes: Int,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = {
            Column {
                for (value in PICKER_VALUES) {
                    TextButton(onClick = { onPick(value) }) {
                        Text(
                            text = stringResource(R.string.player_seconds_label, value),
                            color = if (value == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )
}

private val PICKER_VALUES = listOf(5, 10, 30, 60)

@Composable
private fun playerTitle(state: PlaybackUiState): String = when (state) {
    is PlaybackUiState.Loaded -> state.book.title
    else -> ""
}

@Composable
private fun chapterDisplayTitle(state: PlaybackUiState.Loaded): String {
    val ch = state.currentChapter
    return when {
        ch?.title != null -> ch.title
        ch != null -> stringResource(R.string.player_unknown_chapter, ch.chapterIndex + 1)
        else -> ""
    }
}

private fun positionInChapter(state: PlaybackUiState.Loaded): Long {
    val chapterStart = state.currentChapter?.positionInBookMs ?: 0L
    return (state.positionInBookMs - chapterStart).coerceAtLeast(0L)
}

