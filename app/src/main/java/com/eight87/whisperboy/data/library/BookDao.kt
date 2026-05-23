package com.eight87.whisperboy.data.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE active = 1 ORDER BY title COLLATE NOCASE")
    fun observeActive(): Flow<List<BookEntity>>

    /**
     * Per-book absolute resume position for the library cover grid / list. The LEFT JOIN
     * picks the chapter row at the saved `currentChapterIndex`; `COALESCE(...,0)` covers
     * the (rare) case where a book has no chapter at that index (e.g. an unenriched row
     * scanned before chapters landed) — the projection then degrades to just
     * `position_in_chapter_ms`, which is 0 for un-started books.
     */
    @Query(
        """
        SELECT b.bookId AS bookId,
               COALESCE(c.position_in_book_ms, 0) + b.position_in_chapter_ms AS positionInBookMs
          FROM books b
          LEFT JOIN chapters c
            ON c.bookId = b.bookId AND c.chapterIndex = b.currentChapterIndex
         WHERE b.active = 1
        """
    )
    fun observeActiveBookProgress(): Flow<List<BookProgress>>

    @Query("SELECT * FROM books WHERE bookId = :id LIMIT 1")
    fun observeById(id: String): Flow<BookEntity?>

    /**
     * R.F.9 — per-author detail flow. The filter happens at the DB layer (with NOCASE
     * collation matching the rest of the catalog), so the UI never filters in Compose.
     */
    @Query(
        """
        SELECT * FROM books
        WHERE active = 1
          AND author = :authorName COLLATE NOCASE
        ORDER BY title COLLATE NOCASE
        """
    )
    fun observeByAuthor(authorName: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE bookId = :id LIMIT 1")
    suspend fun findById(id: String): BookEntity?

    /**
     * Snapshot of all known book IDs (active or not). Used by the rescan coordinator
     * to compute the genuine "new books" delta after a scan — comparing the set of
     * book IDs seen in this scan against the set that existed before. Race-free
     * because it's a synchronous one-shot DAO read, not a Compose-state baseline.
     */
    @Query("SELECT bookId FROM books")
    suspend fun allBookIds(): List<String>

    @Query(
        """
        SELECT * FROM books
        WHERE active = 1
          AND (title LIKE '%' || :query || '%' COLLATE NOCASE
               OR author LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY title COLLATE NOCASE
        """
    )
    suspend fun search(query: String): List<BookEntity>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    /**
     * Scan-time structural update for an EXISTING book row. Deliberately omits the
     * position columns (`currentChapterIndex`, `position_in_chapter_ms`, `lastPlayedAt`,
     * `completedAt`) and the per-book playback knobs (`speed`, `skipSilenceEnabled`,
     * `gain_db`) — those are owned by the playback writer and must never be stomped by
     * a stale scan-time snapshot. Closes the read-modify-write race in
     * `LibraryRepository.applyScan` where a `findById` → `upsert` round-trip could clobber
     * a position written by [setLastPlayedPosition] between the read and the write.
     *
     * Always sets `active = 1` because incoming books in the scan are by definition active.
     * NEW books still flow through [upsert] (above) — they have no prior position to preserve.
     */
    @Query(
        """
        UPDATE books
           SET treeUriString = :treeUriString,
               relativePath = :relativePath,
               title = :title,
               author = :author,
               duration_ms = :durationMs,
               coverPath = :coverPath,
               coverSource = :coverSource,
               active = 1
         WHERE bookId = :bookId
        """
    )
    suspend fun updateStructural(
        bookId: String,
        treeUriString: String,
        relativePath: String,
        title: String,
        author: String?,
        durationMs: Long,
        coverPath: String?,
        coverSource: CoverSource,
    )

    /**
     * Streaming-scan structural-only update: like [updateStructural] but does NOT touch
     * `duration_ms` / `coverPath` / `coverSource`, and forces `enriched = 0` so the row
     * lands in the partial-enriched window until the per-chapter analyzer pass calls
     * [setEnrichment]. Used by `LibraryRepository.applyBookBatch` for existing rows.
     */
    @Query(
        """
        UPDATE books
           SET treeUriString = :treeUriString,
               relativePath = :relativePath,
               title = :title,
               author = :author,
               active = 1,
               enriched = 0
         WHERE bookId = :bookId
        """
    )
    suspend fun updateStructuralPartial(
        bookId: String,
        treeUriString: String,
        relativePath: String,
        title: String,
        author: String?,
    )

    /**
     * Streaming-scan enrichment landing: fills in the per-chapter analyzer-derived fields
     * once the worker has finished a book. Does NOT touch position / playback knobs /
     * `coverSource` (covers user-picked custom covers — those land via [setCoverPath] +
     * [setCoverSource] earlier and stick).
     */
    @Query(
        """
        UPDATE books
           SET duration_ms = :durationMs,
               author = COALESCE(:author, author),
               coverPath = COALESCE(:coverPath, coverPath),
               enriched = 1
         WHERE bookId = :id
        """
    )
    suspend fun setEnrichment(
        id: String,
        durationMs: Long,
        author: String?,
        coverPath: String?,
    )

    @Query("UPDATE books SET active = 0 WHERE treeUriString = :treeUriString")
    suspend fun markRootInactive(treeUriString: String)

    @Query("UPDATE books SET active = 0 WHERE bookId IN (:ids)")
    suspend fun markInactiveByIds(ids: List<String>)

    @Query("UPDATE books SET active = 1 WHERE bookId IN (:ids)")
    suspend fun markActiveByIds(ids: List<String>)

    @Query("UPDATE books SET completedAt = :timestamp WHERE bookId = :id")
    suspend fun setCompletedAt(id: String, timestamp: Long?)

    @Query("UPDATE books SET coverPath = :path WHERE bookId = :id")
    suspend fun setCoverPath(id: String, path: String?)

    @Query("UPDATE books SET coverSource = :source WHERE bookId = :id")
    suspend fun setCoverSource(id: String, source: CoverSource)

    @Query("UPDATE books SET lastPlayedAt = :timestamp WHERE bookId = :id")
    suspend fun setLastPlayedAt(id: String, timestamp: Long?)

    /**
     * Phase P.7 — event-driven position save. Writes the (chapter, in-chapter position,
     * last-played-at) tuple atomically. Voice's "experimental playback persistence"
     * pattern: save on real events (transition / pause / background / shutdown), not on
     * a 1Hz timer. Battery-friendly and survives process death without an in-flight DB
     * write being orphaned.
     */
    @Query(
        """
        UPDATE books
           SET currentChapterIndex = :chapterIndex,
               position_in_chapter_ms = :positionMs,
               lastPlayedAt = :lastPlayedAt
         WHERE bookId = :id
        """
    )
    suspend fun setLastPlayedPosition(id: String, chapterIndex: Int, positionMs: Long, lastPlayedAt: Long)

    @Query("UPDATE books SET speed = :speed WHERE bookId = :id")
    suspend fun setSpeed(id: String, speed: Float)

    @Query("UPDATE books SET skipSilenceEnabled = :enabled WHERE bookId = :id")
    suspend fun setSkipSilence(id: String, enabled: Boolean)

    @Query("UPDATE books SET gain_db = :gainDb WHERE bookId = :id")
    suspend fun setGainDb(id: String, gainDb: Float)

    @Query("DELETE FROM books WHERE bookId = :id")
    suspend fun deleteById(id: String)
}
