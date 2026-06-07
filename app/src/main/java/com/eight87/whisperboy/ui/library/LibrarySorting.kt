package com.eight87.whisperboy.ui.library

import com.eight87.whisperboy.data.library.BookEntity
import com.eight87.whisperboy.data.library.BookFilter
import com.eight87.whisperboy.data.library.BookSortKey
import java.text.Collator
import java.util.Locale

/**
 * Phase E.2 / E.3 — pure-function predicates and comparators for the library grid / list.
 *
 * The enums ([BookSortKey], [BookFilter], [com.eight87.whisperboy.data.library.GridMode])
 * live in `data/library/` so the persisted [com.eight87.whisperboy.data.library.LibraryUiSettings]
 * facet can reference them without inverting the dependency direction. These functions stay
 * here — they're consumed by the composable and have no business in the data layer.
 *
 * No Compose imports, so unit-testable without `createComposeRule()` (R.D.3 discipline).
 */

fun filterBooks(books: List<BookEntity>, filter: BookFilter): List<BookEntity> = when (filter) {
    BookFilter.All -> books
    BookFilter.Current -> books.filter { it.lastPlayedAt != null && it.completedAt == null }
    BookFilter.NotStarted -> books.filter { it.lastPlayedAt == null && it.completedAt == null }
    BookFilter.Completed -> books.filter { it.completedAt != null }
}

/**
 * Phase E.4 — in-memory search over title + author.
 *
 * Case-insensitive substring match. Empty / blank query returns the input unchanged (no
 * filtering). Same call tonearmboy made for its library tabs — DB-side FTS4 becomes meaningful
 * at library sizes much larger than the typical user collection; revisit if a user with 1000+
 * books reports typing lag.
 */
fun searchBooks(books: List<BookEntity>, query: String): List<BookEntity> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return books
    return books.filter { book ->
        book.title.contains(trimmed, ignoreCase = true) ||
            (book.author?.contains(trimmed, ignoreCase = true) == true)
    }
}

/**
 * Pure-function sort. The comparator instances are constructed per-call so they pick up
 * the current default locale (R.A.Q for collation across languages — Voice does the same).
 *
 * [progressByBookId] maps `bookId → book-absolute resume position` (the same map the grid /
 * list tiles render from). It's only consulted by [BookSortKey.Progress]; the other keys
 * ignore it, so it defaults to empty for the call sites (and unit tests) that don't sort by
 * progress.
 */
fun sortedBooks(
    books: List<BookEntity>,
    key: BookSortKey,
    progressByBookId: Map<String, Long> = emptyMap(),
): List<BookEntity> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    return when (key) {
        // Recent: `lastPlayedAt` desc, nulls last; ties break on title for stable order.
        BookSortKey.Recent -> books.sortedWith(
            compareByDescending<BookEntity> { it.lastPlayedAt != null }
                .thenByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }
                .thenBy(collator) { it.title }
        )
        BookSortKey.Title -> books.sortedWith(compareBy(collator) { it.title })
        // Author: nulls last (unknown-author group sorts to the end), then title within author.
        BookSortKey.Author -> books.sortedWith(
            compareBy<BookEntity> { it.author.isNullOrBlank() }
                .thenBy(collator) { it.author.orEmpty() }
                .thenBy(collator) { it.title }
        )
        // Progress: fraction-finished desc (closest-to-done first), un-started (0%) bucket
        // sinks to the end; ties break on title for a stable order.
        BookSortKey.Progress -> books.sortedWith(
            compareByDescending<BookEntity> {
                fractionFinished(progressByBookId[it.bookId] ?: 0L, it.durationMs)
            }.thenBy(collator) { it.title }
        )
    }
}

/** Book-absolute resume fraction, clamped to `0f..1f`. Mirrors the grid tile's progress math. */
private fun fractionFinished(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L || positionMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * Phase E.3 — `(itemIndex, label)` pairs for the [com.eight87.whisperboy.ui.common.FastScrollbar]
 * section-letter chips, sort-aware.
 *
 * - [BookSortKey.Title] → first letter of the title (uppercase; non-letters bucket into "#")
 * - [BookSortKey.Author] → first letter of the author (unknown-author bucket is "?")
 * - [BookSortKey.Recent] → coarse-grained recency bucket label ("Today" / "Week" / "Month" /
 *   "Older" / "Never"). The chip labels are short so they fit the track; the bucket math
 *   leans on `nowMs` so it's deterministic per call.
 *
 * Duplicate-key crash gotcha (tonearmboy `d75b542`): these labels never become item keys in
 * a LazyGrid — they live only on the FastScrollbar track. If a future header-item type lands
 * in the same grid, header keys must be `"section:$label"` not just `label` to avoid colliding
 * with book ids.
 */
fun sectionStartsFor(
    books: List<BookEntity>,
    key: BookSortKey,
    nowMs: Long = System.currentTimeMillis(),
    progressByBookId: Map<String, Long> = emptyMap(),
): List<Pair<Int, String>> {
    if (books.isEmpty()) return emptyList()
    val labelOf: (BookEntity) -> String = when (key) {
        BookSortKey.Title -> { book -> firstLetterLabel(book.title) }
        BookSortKey.Author -> { book ->
            if (book.author.isNullOrBlank()) "?" else firstLetterLabel(book.author)
        }
        BookSortKey.Recent -> { book -> recencyBucketLabel(book.lastPlayedAt, nowMs) }
        BookSortKey.Progress -> { book ->
            progressBucketLabel(fractionFinished(progressByBookId[book.bookId] ?: 0L, book.durationMs))
        }
    }
    val seen = mutableSetOf<String>()
    val out = mutableListOf<Pair<Int, String>>()
    books.forEachIndexed { index, book ->
        val label = labelOf(book)
        if (seen.add(label)) out += index to label
    }
    return out
}

private fun firstLetterLabel(text: String): String {
    val first = text.trimStart().firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/**
 * Coarse percent-finished chip label for the fast-scroll track, matching the
 * [BookSortKey.Progress] descending order: 100% → 75% → 50% → 25% → un-started ("0%").
 */
private fun progressBucketLabel(fraction: Float): String = when {
    fraction >= 1f -> "100%"
    fraction >= 0.75f -> "75%"
    fraction >= 0.5f -> "50%"
    fraction >= 0.25f -> "25%"
    fraction > 0f -> "1%"
    else -> "0%"
}

private fun recencyBucketLabel(lastPlayedAt: Long?, nowMs: Long): String {
    if (lastPlayedAt == null) return "Never"
    val ageMs = nowMs - lastPlayedAt
    val oneDay = 24L * 60 * 60 * 1000
    return when {
        ageMs < oneDay -> "Today"
        ageMs < 7 * oneDay -> "Week"
        ageMs < 30 * oneDay -> "Month"
        else -> "Older"
    }
}
