package com.eight87.whisperboy.data.library

import androidx.room.withTransaction
import com.eight87.whisperboy.data.playback.PlaybackSettings
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Concrete implementation of every narrow data interface in `data/library/`. Lives behind
 * [BookSource] / [ChapterSource] / [BookmarkSource] / [ScanWriter] when handed out from
 * [com.eight87.whisperboy.AppGraph] — composables and ViewModels never see this concrete
 * type (R.A.2 pattern from `docs/plans/refactor-solid.md`).
 *
 * The class is `internal` so module-internal code can pass it around as a single object but
 * external callers can only use the narrow handles `AppGraph` exposes.
 */
internal class LibraryRepository(
    private val database: LibraryDatabase,
    private val coverStore: CoverStore,
    private val playbackSettings: PlaybackSettings,
) : BookSource, ChapterSource, BookmarkSource, ScanWriter {

    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()

    // ---- BookSource ----

    override fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeActive()

    override fun observeBookProgress(): Flow<List<BookProgress>> = bookDao.observeActiveBookProgress()

    override fun observeBook(id: String): Flow<BookEntity?> = bookDao.observeById(id)

    override fun observeBooksByAuthor(authorName: String): Flow<List<BookEntity>> =
        bookDao.observeByAuthor(authorName)

    override suspend fun search(query: String): List<BookEntity> = bookDao.search(query)

    override suspend fun allBookIds(): Set<String> = bookDao.allBookIds().toSet()

    override suspend fun markCompleted(bookId: String) {
        bookDao.setCompletedAt(bookId, System.currentTimeMillis())
    }

    override suspend fun markNotStarted(bookId: String) {
        // "Not started" semantically clears both completion AND last-played; the book returns
        // to the NotStarted filter bucket. Position is left intact — re-marking played picks up
        // where the user was, which is friendlier than wiping progress.
        bookDao.setCompletedAt(bookId, null)
        bookDao.setLastPlayedAt(bookId, null)
    }

    override suspend fun forgetBook(bookId: String) {
        // Phase E.5 contract: "Forget" deletes the row + bookmarks (FK CASCADE handles chapters
        // and bookmarks). It does NOT delete the audio files — whisperboy never touches the
        // user's library on disk. On a subsequent rescan the book reappears with default state.
        database.withTransaction { bookDao.deleteById(bookId) }
        coverStore.deleteCover(bookId)
    }

    override suspend fun setSpeed(bookId: String, speed: Float) {
        bookDao.setSpeed(bookId, speed)
    }

    override suspend fun setSkipSilence(bookId: String, enabled: Boolean) {
        bookDao.setSkipSilence(bookId, enabled)
    }

    override suspend fun setGain(bookId: String, gainDb: Float) {
        bookDao.setGainDb(bookId, gainDb)
    }

    override suspend fun updatePosition(
        bookId: String,
        chapterIndex: Int,
        positionInChapterMs: Long,
        lastPlayedAt: Long,
    ) {
        bookDao.setLastPlayedPosition(bookId, chapterIndex, positionInChapterMs, lastPlayedAt)
    }

    override suspend fun setCustomCover(bookId: String, bytes: ByteArray) {
        // Phase A.6 (`cover-art.md`): write the bytes through [CoverStore] (atomic tmp+rename),
        // then flip the cached row to point at the new path with `coverSource = Custom` so
        // `applyScan` leaves it alone on subsequent rescans.
        val path = coverStore.writeCover(bookId, bytes)
        bookDao.setCoverPath(bookId, path)
        bookDao.setCoverSource(bookId, CoverSource.Custom)
    }

    // ---- ChapterSource ----

    override fun observeChaptersForBook(bookId: String): Flow<List<ChapterEntity>> =
        chapterDao.observeForBook(bookId)

    override suspend fun chaptersFor(bookId: String): List<ChapterEntity> =
        chapterDao.chaptersFor(bookId)

    // ---- BookmarkSource ----

    override fun observeBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> =
        bookmarkDao.observeForBook(bookId)

    override suspend fun addBookmark(
        bookId: String,
        chapterId: String?,
        title: String?,
        positionInBookMs: Long,
        setBySleepTimer: Boolean,
    ) {
        bookmarkDao.insert(
            BookmarkEntity(
                bookmarkId = UUID.randomUUID().toString(),
                bookId = bookId,
                chapterId = chapterId,
                title = title,
                positionInBookMs = positionInBookMs,
                addedAt = System.currentTimeMillis(),
                setBySleepTimer = setBySleepTimer,
            )
        )
    }

    override suspend fun renameBookmark(id: String, title: String?) {
        bookmarkDao.rename(id, title)
    }

    override suspend fun deleteBookmark(id: String) {
        bookmarkDao.deleteById(id)
    }

    // ---- ScanWriter ----

    override suspend fun applyScan(snapshot: ScanSnapshot) {
        // 0. Snapshot the global per-book defaults ONCE for this scan. New rows inherit
        //    these on first sight (J.4 / K.2); existing rows preserve their per-book
        //    overrides. Read outside the Room transaction so we don't hold the DB lock
        //    waiting on DataStore.
        val defaultSpeed = playbackSettings.defaultSpeed.first()
        val defaultSkipSilence = playbackSettings.defaultSkipSilence.first()
        val defaultGainDb = playbackSettings.defaultGainDb.first()

        // 1. Land cover bytes on disk OUTSIDE the Room transaction. Long file IO inside the
        //    txn would hold the database lock; a rollback would also leave the on-disk file
        //    orphaned. Write first, then commit rows that reference the path.
        //
        //    Phase A.6 (`cover-art.md`): a per-book lookup respects user-picked custom covers
        //    — if the existing cached row has `coverSource = Custom`, keep its `coverPath`
        //    and tag the new row as `Custom` too, so the scanner-derived bytes (sidecar
        //    folder image / embedded tag) don't overwrite the user's pick. Acceptable to
        //    fan out per-book here: scans run infrequently and the typical library is small.
        //
        //    J.4 / K.2 default propagation: this same per-book existing-row fetch carries
        //    a [PerBookState] alongside the cover decision. If `existing` is null this is a
        //    first-time scan for that book — copy the global defaults into the new entity.
        //    Otherwise preserve the per-book row's `speed` / `skipSilenceEnabled` / `gainDb`
        //    + the user's resume position + `lastPlayedAt`. Editing the globals later does
        //    NOT retro-write — globals are first-scan-only seeds.
        val booksWithScanData: List<Pair<ScannedBook, PerBookScanData>> =
            snapshot.books.map { book ->
                val existing = bookDao.findById(book.bookId)
                val (coverPath, coverSource) = if (existing?.coverSource == CoverSource.Custom) {
                    existing.coverPath to CoverSource.Custom
                } else {
                    val derived = book.embeddedCoverBytes?.let { bytes ->
                        coverStore.writeCover(book.bookId, bytes)
                    } ?: book.coverPath
                    derived to CoverSource.Scanned
                }
                val data = PerBookScanData(
                    coverPath = coverPath,
                    coverSource = coverSource,
                    isExisting = existing != null,
                    // First-time scan: seed per-book settings from the global defaults (J.4 / K.2).
                    // For existing rows these values are IGNORED — `updateStructural` doesn't
                    // touch the position / playback-knob columns, closing the read-modify-write
                    // race vs. concurrent position writes from playback.
                    speed = defaultSpeed,
                    skipSilenceEnabled = defaultSkipSilence,
                    gainDb = defaultGainDb,
                )
                book to data
            }

        // 2. Group incoming book ids by treeUri so soft-delete sweeps per touched root.
        //    Pattern: for each touched root, mark every active book from that root inactive,
        //    then upsert the incoming ones (which sets `active = true` again via the entity
        //    field). Books in the root that are NOT in the incoming set stay inactive —
        //    bookmarks + per-book settings preserved by virtue of the row staying alive.
        val touchedRoots: Set<String> = snapshot.books.map { it.treeUriString }.toSet()

        // 3. One Room transaction for atomic correctness across cover-path commits + book
        //    upserts + chapter replacements + soft-delete sweeps.
        database.withTransaction {
            for (root in touchedRoots) {
                bookDao.markRootInactive(root)
            }
            for ((book, data) in booksWithScanData) {
                if (data.isExisting) {
                    // Existing row: structural-only update so concurrent position writes from
                    // playback (between `findById` above and this DAO call) can't be stomped.
                    bookDao.updateStructural(
                        bookId = book.bookId,
                        treeUriString = book.treeUriString,
                        relativePath = book.relativePath,
                        title = book.title,
                        author = book.author,
                        durationMs = book.durationMs,
                        coverPath = data.coverPath,
                        coverSource = data.coverSource,
                    )
                } else {
                    // New row: full insert seeded with global playback defaults + zero position.
                    bookDao.upsert(book.toEntity(data))
                }
                // Replace the chapter set wholesale. CASCADE on chapter delete is fine here:
                // bookmarks have `onDelete = SET_NULL` on chapterId, so they survive a
                // chapter-row disappearing (the bookmark just unpins from the chapter and
                // pins on the book-level position).
                chapterDao.deleteForBook(book.bookId)
                chapterDao.upsertAll(book.chapters.map { it.toEntity(book.bookId) })
            }
        }
    }

    override suspend fun applyBookBatch(books: List<ScannedBook>) {
        if (books.isEmpty()) return
        // Seed playback defaults once for any first-time-scan rows in this batch.
        val defaultSpeed = playbackSettings.defaultSpeed.first()
        val defaultSkipSilence = playbackSettings.defaultSkipSilence.first()
        val defaultGainDb = playbackSettings.defaultGainDb.first()

        database.withTransaction {
            for (book in books) {
                val existing = bookDao.findById(book.bookId)
                if (existing != null) {
                    // Existing row: structural-partial update — refresh title/author/path,
                    // do NOT touch duration / cover (worker fills those via applyBookEnrichment).
                    bookDao.updateStructuralPartial(
                        bookId = book.bookId,
                        treeUriString = book.treeUriString,
                        relativePath = book.relativePath,
                        title = book.title,
                        author = book.author,
                    )
                } else {
                    // New row: zero duration, null cover, `enriched = false`. Cover bytes are
                    // landed later by the worker via `applyBookEnrichment`.
                    bookDao.upsert(
                        BookEntity(
                            bookId = book.bookId,
                            treeUriString = book.treeUriString,
                            relativePath = book.relativePath,
                            title = book.title,
                            author = book.author,
                            durationMs = 0L,
                            coverPath = null,
                            coverSource = CoverSource.Scanned,
                            currentChapterIndex = 0,
                            positionInChapterMs = 0L,
                            speed = defaultSpeed,
                            skipSilenceEnabled = defaultSkipSilence,
                            gainDb = defaultGainDb,
                            lastPlayedAt = null,
                            active = true,
                            enriched = false,
                        )
                    )
                }
                // Replace chapter set wholesale with structural rows (zero durations, file URIs
                // present). Worker later replaces these again with enrichment-filled durations.
                chapterDao.deleteForBook(book.bookId)
                chapterDao.upsertAll(book.chapters.map { it.toEntity(book.bookId) })
            }
        }
    }

    override suspend fun applyBookEnrichment(enrichedBook: ScannedBook) {
        // Persist cover bytes (scanner-owned). Respect custom user covers — don't stomp them.
        val existing = bookDao.findById(enrichedBook.bookId)
        val coverPath: String? = when {
            existing == null -> null
            existing.coverSource == CoverSource.Custom -> existing.coverPath
            else -> enrichedBook.embeddedCoverBytes?.let { bytes ->
                coverStore.writeCover(enrichedBook.bookId, bytes)
            } ?: enrichedBook.coverPath ?: existing.coverPath
        }

        database.withTransaction {
            bookDao.setEnrichment(
                id = enrichedBook.bookId,
                durationMs = enrichedBook.durationMs,
                author = enrichedBook.author,
                coverPath = coverPath,
            )
            // Replace chapter rows with enrichment-filled durations / titles.
            chapterDao.deleteForBook(enrichedBook.bookId)
            chapterDao.upsertAll(enrichedBook.chapters.map { it.toEntity(enrichedBook.bookId) })
        }
    }

    override suspend fun sweepInactiveRoots(touchedRoots: Set<String>, seenBookIds: Set<String>) {
        if (touchedRoots.isEmpty()) return
        database.withTransaction {
            for (root in touchedRoots) {
                bookDao.markRootInactive(root)
            }
            // Re-activate the ones the streaming pipeline actually observed.
            // markRootInactive set everything to 0; the per-book upserts in applyBookBatch
            // already set active = true, but updateStructuralPartial also re-sets it. The
            // sweep order needs to be: sweep FIRST, then individual writes — but since the
            // streaming pipeline runs writes BEFORE this sweep, we have to flip the seen ids
            // back to active here.
            if (seenBookIds.isNotEmpty()) {
                bookDao.markActiveByIds(seenBookIds.toList())
            }
        }
    }

    private fun ScannedBook.toEntity(data: PerBookScanData): BookEntity = BookEntity(
        bookId = bookId,
        treeUriString = treeUriString,
        relativePath = relativePath,
        title = title,
        author = author,
        durationMs = durationMs,
        coverPath = data.coverPath,
        coverSource = data.coverSource,
        currentChapterIndex = 0,
        positionInChapterMs = 0L,
        speed = data.speed,
        skipSilenceEnabled = data.skipSilenceEnabled,
        gainDb = data.gainDb,
        lastPlayedAt = null,
        active = true,
    )

    /**
     * Per-book scan state assembled in [applyScan]. Only consumed by [toEntity] for NEW rows;
     * existing rows go through [BookDao.updateStructural] which preserves their position
     * + per-book playback knobs. [isExisting] picks the branch.
     */
    private data class PerBookScanData(
        val coverPath: String?,
        val coverSource: CoverSource,
        val isExisting: Boolean,
        val speed: Float,
        val skipSilenceEnabled: Boolean,
        val gainDb: Float,
    )

    private fun ScannedChapter.toEntity(bookId: String): ChapterEntity = ChapterEntity(
        chapterId = chapterId,
        bookId = bookId,
        chapterIndex = chapterIndex,
        title = title,
        durationMs = durationMs,
        fileUri = fileUri,
        positionInBookMs = positionInBookMs,
    )
}
