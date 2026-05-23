package com.eight87.whisperboy.data.library

import kotlinx.coroutines.flow.Flow

/**
 * Narrow data interface (Phase R.A pattern) for read access to the book catalog.
 *
 * Composables and ViewModels depend on this contract, never on the concrete repository
 * (which lands in Phase D.4) or on [BookDao] directly. The two-method footprint is the
 * minimum the library / player / detail screens need.
 *
 * Returns [BookEntity] directly for now; if Phase D.2's scanner produces fields the cache
 * doesn't keep, R.F.4 (Book vs ScannedBook split) will introduce a domain wrapper here.
 */
interface BookSource {

    fun observeBooks(): Flow<List<BookEntity>>

    /**
     * Stream of book-absolute resume positions per active book. The library cover grid /
     * list use this to render the per-tile progress affordance (progress bar overlay on
     * the cover + remaining time / percentage line). Joined against the chapters table at
     * the DB tier so the UI doesn't have to load every book's full chapter list.
     */
    fun observeBookProgress(): Flow<List<BookProgress>>

    fun observeBook(id: String): Flow<BookEntity?>

    /**
     * R.F.9 — detail-screen flow filtered on the data layer. The UI does not filter
     * in Compose; the [BookDao] query applies the `author = :authorName COLLATE NOCASE`
     * predicate at the DB tier so the screen receives the ready-to-render list.
     * Matches Voice's per-author detail pattern + tonearmboy R.F.12.
     */
    fun observeBooksByAuthor(authorName: String): Flow<List<BookEntity>>

    suspend fun search(query: String): List<BookEntity>

    /**
     * Snapshot of all known book IDs (active or not). The rescan coordinator uses this at
     * scan start to capture the existing-book baseline so the "Found N new books" snackbar
     * counts the real delta (`seenBookIds - existingIds`), not a race-prone Compose-side
     * `books.size` baseline that captures 0 when the Flow hasn't emitted yet.
     */
    suspend fun allBookIds(): Set<String>

    /**
     * Phase E.5 — book actions. Mark / unmark completion (sets / clears `completedAt`),
     * forget a book entirely (deletes the row + cascading bookmarks/chapters via FK).
     *
     * Kept on the same interface for now — splitting into a separate `BookCommandSource`
     * facet earns its keep when a second consumer (e.g. multi-select bar) appears that
     * wants write access without read access. Currently both consumers also read books,
     * so the single interface is fine.
     */
    suspend fun markCompleted(bookId: String)

    suspend fun markNotStarted(bookId: String)

    suspend fun forgetBook(bookId: String)

    /**
     * Phase A.6 (`cover-art.md`) — replace the cover for [bookId] with the given bytes and
     * mark its [BookEntity.coverSource] as [CoverSource.Custom] so subsequent rescans leave
     * the user's pick alone. Bytes are typically loaded from a SAF `OpenDocument` result.
     */
    suspend fun setCustomCover(bookId: String, bytes: ByteArray)

    /**
     * Phase J — per-book playback knobs. The UI calls these via `PlaybackController.setSpeed`
     * / `setSkipSilence` / `setGain`; the controller persists through this surface so the value
     * sticks across app restarts AND is applied back on the next [BookCommands.playBook] call.
     *
     * Coercion to legal ranges happens at the controller layer (or the UI Slider's `valueRange`);
     * the data layer trusts callers.
     */
    suspend fun setSpeed(bookId: String, speed: Float)

    suspend fun setSkipSilence(bookId: String, enabled: Boolean)

    suspend fun setGain(bookId: String, gainDb: Float)

    /**
     * Phase P.7 — event-driven position save. Called by [com.eight87.whisperboy.playback.PlaybackController]
     * on chapter transition, on pause, on app backgrounding, and on service teardown. Writes
     * `currentChapterIndex` + `position_in_chapter_ms` + `lastPlayedAt` atomically.
     */
    suspend fun updatePosition(bookId: String, chapterIndex: Int, positionInChapterMs: Long, lastPlayedAt: Long)
}
