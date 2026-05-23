package com.eight87.whisperboy.data.library

/**
 * Book-absolute resume position. Computed at the DB tier by joining `books` against the
 * `chapters` row at `currentChapterIndex` so the library cover grid / list can render the
 * per-tile progress affordance without loading every book's full chapter list.
 */
data class BookProgress(
    val bookId: String,
    val positionInBookMs: Long,
)
