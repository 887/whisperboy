package com.eight87.whisperboy.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.BookEntity
import com.eight87.whisperboy.data.library.BookSource
import com.eight87.whisperboy.data.library.ChapterEntity
import com.eight87.whisperboy.data.library.ChapterSource
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.io.File

/**
 * Phase N — Android Auto / Wear-OS / system media-browse callback.
 *
 * Returns a four-folder browseable root (Currently listening / Not started / All books /
 * Authors) walked by `onGetChildren`; each leaf is a book `MediaItem` keyed by
 * `BOOK_PREFIX + bookId`. Voice search arrives via [onAddMediaItems] /
 * [onSetMediaItems] as a `requestMetadata.searchQuery` on a single placeholder item;
 * we delegate to [BookSource.search] and fall back to fuzzy author-contains.
 *
 * Custom commands (sleep timer / speed / skip-silence) are registered in [onConnect]
 * so Auto's UI surfaces them as action buttons. Phase J's missing pieces (skip
 * silence, gain) log + return success cleanly — the car shouldn't see an error toast
 * for a command we accept but can't yet honour fully.
 *
 * SOLID notes — narrow dependencies (R.A pattern): the callback takes only
 * [BookSource] (read), [TransportCommands] / [BookCommands] (write), and
 * [SleepTimerCommands]. It never sees the concrete `PlaybackController` /
 * `LibraryRepository`.
 */
class WhisperboyLibrarySessionCallback(
    context: Context,
    private val bookSource: BookSource,
    private val chapterSource: ChapterSource,
    private val transportCommands: TransportCommands,
    private val bookCommands: BookCommands,
    private val sleepTimerCommands: SleepTimerCommands,
    private val scope: CoroutineScope,
    // Phase J — direct handles for the two knobs that can't ride [TransportCommands] over the
    // cross-process [androidx.media3.session.MediaController] boundary:
    //  * `ExoPlayer.skipSilenceEnabled` is an ExoPlayer-only property (not on [Player]).
    //  * The gain processor's `setGainDb` is a custom audio-pipeline knob with no IPC surface.
    // Both are reached via custom session commands that land here on the service side.
    private val exoPlayer: ExoPlayer,
    private val volumeGain: VolumeGainAudioProcessor,
) : MediaLibrarySession.Callback {

    private val appContext = context.applicationContext

    // ---------------------------------------------------------------- onConnect

    override fun onConnect(
        session: MediaSession,
        controller: ControllerInfo,
    ): ConnectionResult {
        val base = super.onConnect(session, controller)
        val sessionCommands = base.availableSessionCommands.buildUpon()
            .add(SessionCommand(CustomCommands.SET_SLEEP_TIMER, Bundle.EMPTY))
            .add(SessionCommand(CustomCommands.SET_SPEED, Bundle.EMPTY))
            .add(SessionCommand(CustomCommands.SET_SKIP_SILENCE, Bundle.EMPTY))
            .add(SessionCommand(CustomCommands.SET_GAIN_DB, Bundle.EMPTY))
            .build()
        return ConnectionResult.accept(sessionCommands, base.availablePlayerCommands)
    }

    // ---------------------------------------------------------------- browse tree

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = browseableFolder(
            id = ROOT_ID,
            title = appContext.getString(R.string.auto_browse_root_title),
        )
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val item = resolveItem(mediaId)
        if (item != null) LibraryResult.ofItem(item, null)
        else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children: List<MediaItem>? = when {
            parentId == ROOT_ID -> rootChildren()
            parentId == CURRENT_ID -> currentlyListeningChildren()
            parentId == NOT_STARTED_ID -> notStartedChildren()
            parentId == ALL_ID -> allBooksChildren()
            parentId == AUTHORS_ID -> authorChildren()
            parentId.startsWith(AUTHOR_PREFIX) -> {
                val author = parentId.removePrefix(AUTHOR_PREFIX)
                booksByAuthorChildren(author)
            }
            else -> null
        }
        if (children != null) {
            LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
        } else {
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
    }

    private fun rootChildren(): List<MediaItem> = listOf(
        browseableFolder(
            CURRENT_ID,
            appContext.getString(R.string.auto_browse_currently_listening),
            R.drawable.auto_browse_current,
        ),
        browseableFolder(
            NOT_STARTED_ID,
            appContext.getString(R.string.auto_browse_not_started),
            R.drawable.auto_browse_not_started,
        ),
        browseableFolder(
            ALL_ID,
            appContext.getString(R.string.auto_browse_all_books),
            R.drawable.auto_browse_all_books,
        ),
        browseableFolder(
            AUTHORS_ID,
            appContext.getString(R.string.auto_browse_authors),
            R.drawable.auto_browse_authors,
        ),
    )

    private suspend fun currentlyListeningChildren(): List<MediaItem> {
        val books = bookSource.observeBooks().first()
            .filter { it.active && it.lastPlayedAt != null && it.completedAt == null }
            .sortedByDescending { it.lastPlayedAt ?: 0L }
        return books.map(::bookMediaItem)
    }

    private suspend fun notStartedChildren(): List<MediaItem> {
        val books = bookSource.observeBooks().first()
            .filter { it.active && it.lastPlayedAt == null && it.completedAt == null }
            .sortedBy { it.title.lowercase() }
        return books.map(::bookMediaItem)
    }

    private suspend fun allBooksChildren(): List<MediaItem> {
        val books = bookSource.observeBooks().first()
            .filter { it.active }
            .sortedBy { it.title.lowercase() }
        return books.map(::bookMediaItem)
    }

    private suspend fun authorChildren(): List<MediaItem> {
        val authors = bookSource.observeBooks().first()
            .filter { it.active }
            .mapNotNull { it.author?.takeIf(String::isNotBlank) }
            .distinct()
            .sortedBy { it.lowercase() }
        return authors.map { author ->
            browseableFolder(AUTHOR_PREFIX + author, author)
        }
    }

    private suspend fun booksByAuthorChildren(author: String): List<MediaItem> {
        val books = bookSource.observeBooks().first()
            .filter { it.active && (it.author ?: "") == author }
            .sortedBy { it.title.lowercase() }
        return books.map(::bookMediaItem)
    }

    /**
     * Resolve a single `mediaId` to a `MediaItem` for `onGetItem`. Used when the car
     * UI requests metadata for a known leaf (cover render, "playing next" hint).
     */
    private suspend fun resolveItem(mediaId: String): MediaItem? {
        if (mediaId == ROOT_ID) {
            return browseableFolder(
                id = ROOT_ID,
                title = appContext.getString(R.string.auto_browse_root_title),
            )
        }
        if (mediaId.startsWith(BOOK_PREFIX)) {
            val bookId = mediaId.removePrefix(BOOK_PREFIX)
            val book = bookSource.observeBook(bookId).first() ?: return null
            return bookMediaItem(book)
        }
        return null
    }

    // ---------------------------------------------------------------- voice search

    /**
     * Called when the car taps a leaf (mediaId = `BOOK_PREFIX + bookId`) or when the
     * user issues a voice search ("Hey Google, play <X> on whisperboy"), in which case
     * the system hands us a single placeholder `MediaItem` with the spoken query on its
     * `requestMetadata.searchQuery`. We resolve to a real book + chapter list and start
     * playback at the last-saved position.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future {
        mediaItems.mapNotNull { resolveForPlayback(it) }
    }

    private suspend fun resolveForPlayback(item: MediaItem): MediaItem? {
        // Items arriving from our own PlaybackController.startPlayback already have a URI
        // and are ready to hand to ExoPlayer. Only Auto / voice-search / lockscreen-resume
        // entries arrive as URI-less placeholders (bookId-prefixed mediaId, or a searchQuery
        // on requestMetadata) that need the lookup-then-replace dance below. Passing
        // already-playable items through unchanged was the missing case — without this
        // guard `mapNotNull` dropped every chapter and the session got zero items, which
        // ExoPlayer reported as BUFFERING → ENDED with no error and no notification.
        if (item.localConfiguration?.uri != null) return item
        val explicitId = item.mediaId.takeIf { it.startsWith(BOOK_PREFIX) }
            ?.removePrefix(BOOK_PREFIX)
        if (explicitId != null) {
            val book = bookSource.observeBook(explicitId).first() ?: return null
            scope.launch { bookCommands.playBook(book.bookId) }
            return playableMediaItem(book)
        }
        val query = item.requestMetadata.searchQuery?.trim()
        if (!query.isNullOrBlank()) {
            val match = resolveSearchQuery(query) ?: return null
            scope.launch { bookCommands.playBook(match.bookId) }
            return playableMediaItem(match)
        }
        return null
    }

    /**
     * Two-stage resolution:
     *
     *  1. Repository's [BookSource.search] (which does a Room `LIKE` across title + author).
     *     Prefer the result whose `title` exactly matches the query first; otherwise the
     *     first row.
     *  2. If nothing matched, fall back to fuzzy `author.contains(query)` over the full
     *     active library — voice queries are often just "play <author>" without a title.
     */
    private suspend fun resolveSearchQuery(query: String): BookEntity? {
        val hits = bookSource.search(query)
        val exact = hits.firstOrNull { it.title.equals(query, ignoreCase = true) }
        if (exact != null) return exact
        if (hits.isNotEmpty()) return hits.first()
        val needle = query.lowercase()
        return bookSource.observeBooks().first()
            .firstOrNull { book ->
                book.active && (book.author?.lowercase()?.contains(needle) == true)
            }
    }

    // ---------------------------------------------------------------- playback resumption

    /**
     * Called by Media3 when the user presses a media button (Bluetooth play, lockscreen play
     * tile, headset hook) while the service is **cold-dead** — process not running, session
     * never connected. Without this override, the press is silently dropped; the user taps
     * play on their headphones and nothing happens because the system has no resume queue.
     *
     * Resolution: pick the most-recently-played active book (highest [BookEntity.lastPlayedAt]),
     * load its chapters, and hand Media3 a `MediaItemsWithStartPosition` seeded at the book's
     * saved `currentChapterIndex` + `positionInChapterMs`. Media3 then sets the items,
     * prepares the player, and dispatches the queued play command.
     *
     * Mirrors Voice's `LibrarySessionCallback.onPlaybackResumption` shape. No corresponding
     * unit test — this path requires the system MediaButtonReceiver wakeup, which only
     * fires in an instrumented Android environment, not Robolectric.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: ControllerInfo,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val candidate = bookSource.observeBooks().first()
            .filter { it.active && it.lastPlayedAt != null }
            .maxByOrNull { it.lastPlayedAt ?: 0L }
            ?: throw IllegalStateException("No resumable book available")
        val chapters = chapterSource.chaptersFor(candidate.bookId)
        if (chapters.isEmpty()) {
            throw IllegalStateException("Book ${candidate.bookId} has no chapters")
        }
        val items = chapters.map { ch -> resumptionMediaItem(candidate, ch) }
        val startIndex = candidate.currentChapterIndex.coerceIn(0, items.lastIndex)
        val startPositionMs = candidate.positionInChapterMs.coerceAtLeast(0L)
        MediaItemsWithStartPosition(ImmutableList.copyOf(items), startIndex, startPositionMs)
    }

    private fun resumptionMediaItem(book: BookEntity, chapter: ChapterEntity): MediaItem {
        val uri = chapter.fileUri ?: book.relativePath
        val metadata = MediaMetadata.Builder()
            .setTitle(chapter.title ?: book.title)
            .setArtist(book.author ?: appContext.getString(R.string.auto_browse_unknown_author))
            .setAlbumTitle(book.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .also { meta ->
                book.coverPath?.let { path -> meta.setArtworkUri(Uri.fromFile(File(path))) }
            }
            .setDurationMs(chapter.durationMs)
            .build()
        return MediaItem.Builder()
            .setMediaId(chapter.chapterId)
            .setUri(uri.toUri())
            .setMediaMetadata(metadata)
            .build()
    }

    // ---------------------------------------------------------------- custom commands

    override fun onCustomCommand(
        session: MediaSession,
        controller: ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = scope.future {
        when (customCommand.customAction) {
            CustomCommands.SET_SLEEP_TIMER -> {
                val durationMs = args.getLong(CustomCommands.EXTRA_DURATION_MS, 0L)
                if (durationMs > 0L) {
                    sleepTimerCommands.arm(
                        SleepTimerMode.Timed(kotlin.time.Duration.parse("${durationMs}ms")),
                    )
                } else {
                    sleepTimerCommands.cancel()
                }
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            CustomCommands.SET_SPEED -> {
                val speed = args.getFloat(CustomCommands.EXTRA_SPEED, 1.0f)
                transportCommands.setSpeed(speed)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            CustomCommands.SET_SKIP_SILENCE -> {
                val enabled = args.getBoolean(CustomCommands.EXTRA_ENABLED, false)
                // Phase J — `skipSilenceEnabled` is an ExoPlayer-only property; reach the
                // concrete player directly here on the service side so the next decoded buffer
                // picks up the toggle. Controller-side persistence happens in
                // [PlaybackController.setSkipSilence] alongside the dispatch of this command.
                exoPlayer.skipSilenceEnabled = enabled
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            CustomCommands.SET_GAIN_DB -> {
                val gainDb = args.getFloat(CustomCommands.EXTRA_GAIN_DB, 0f)
                // Phase J — drive the custom [VolumeGainAudioProcessor] held by [PlayerHolder].
                // No-op bypass at 0 dB (processor reports `isActive = false`); any non-zero value
                // re-derives the multiplier and the next decoded frame is scaled.
                volumeGain.setGainDb(gainDb)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            else -> SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
        }
    }

    // ---------------------------------------------------------------- MediaItem helpers

    private fun browseableFolder(
        id: String,
        title: String,
        @androidx.annotation.DrawableRes artworkRes: Int? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .also { meta ->
                if (artworkRes != null) {
                    // m3-expressive D.4 — per-root accent-coloured artwork for Auto's
                    // browse tree. android.resource:// URIs are the documented hand-off
                    // for in-APK drawables that the system-side media browser can
                    // resolve cross-process. Auto's system theme often re-tints these
                    // monochrome on real head units; the colour is a best-effort signal.
                    meta.setArtworkUri(
                        Uri.parse("android.resource://${appContext.packageName}/$artworkRes")
                    )
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun bookMediaItem(book: BookEntity): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author ?: appContext.getString(R.string.auto_browse_unknown_author))
            .setAlbumTitle(book.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .also { meta ->
                book.coverPath?.let { path ->
                    meta.setArtworkUri(Uri.fromFile(File(path)))
                }
            }
            .setDurationMs(book.durationMs)
            .build()
        return MediaItem.Builder()
            .setMediaId(BOOK_PREFIX + book.bookId)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * For onAddMediaItems we return a minimal placeholder pointing at the book's source
     * URI. The actual chapter queue + resume-position seek happens via
     * [BookCommands.playBook], dispatched into `scope` so the controller can attach.
     * Media3 uses the returned item for its own initial timeline; the subsequent
     * `setMediaItems` from `playBook` replaces it with the chapter list.
     */
    private fun playableMediaItem(book: BookEntity): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author ?: appContext.getString(R.string.auto_browse_unknown_author))
            .setAlbumTitle(book.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .also { meta ->
                book.coverPath?.let { path ->
                    meta.setArtworkUri(Uri.fromFile(File(path)))
                }
            }
            .setDurationMs(book.durationMs)
            .build()
        return MediaItem.Builder()
            .setMediaId(BOOK_PREFIX + book.bookId)
            .setUri(book.relativePath.toUri())
            .setMediaMetadata(metadata)
            .build()
    }

    private companion object {
        const val ROOT_ID = "root"
        const val CURRENT_ID = "current"
        const val NOT_STARTED_ID = "not_started"
        const val ALL_ID = "all"
        const val AUTHORS_ID = "authors"
        const val AUTHOR_PREFIX = "author:"
        const val BOOK_PREFIX = "book:"
    }
}
