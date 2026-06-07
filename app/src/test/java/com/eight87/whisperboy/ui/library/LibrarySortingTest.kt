package com.eight87.whisperboy.ui.library

import com.eight87.whisperboy.data.library.BookEntity
import com.eight87.whisperboy.data.library.BookFilter
import com.eight87.whisperboy.data.library.BookSortKey
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortingTest {

    private fun book(
        bookId: String,
        title: String,
        author: String? = null,
        lastPlayedAt: Long? = null,
        completedAt: Long? = null,
    ): BookEntity = BookEntity(
        bookId = bookId,
        treeUriString = "content://x",
        relativePath = bookId,
        title = title,
        author = author,
        durationMs = 1000L,
        lastPlayedAt = lastPlayedAt,
        completedAt = completedAt,
    )

    @Test
    fun `sortedBooks Title sorts alphabetically by title`() {
        val out = sortedBooks(
            listOf(
                book("c", "Charlie"),
                book("a", "Alpha"),
                book("b", "Bravo"),
            ),
            BookSortKey.Title,
        )
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), out.map { it.title })
    }

    @Test
    fun `sortedBooks Author groups unknown-author last`() {
        val out = sortedBooks(
            listOf(
                book("c", "C", author = null),
                book("a", "A", author = "Asimov"),
                book("b", "B", author = "Bradbury"),
                book("d", "D", author = ""),
            ),
            BookSortKey.Author,
        )
        assertEquals(listOf("Asimov", "Bradbury", null, ""), out.map { it.author })
    }

    @Test
    fun `sortedBooks Recent puts most-recently-played first, never-played last`() {
        val out = sortedBooks(
            listOf(
                book("never", "Never", lastPlayedAt = null),
                book("old", "Old", lastPlayedAt = 1000L),
                book("new", "New", lastPlayedAt = 5000L),
            ),
            BookSortKey.Recent,
        )
        assertEquals(listOf("New", "Old", "Never"), out.map { it.title })
    }

    @Test
    fun `sortedBooks Progress orders closest-to-done first, un-started last`() {
        val out = sortedBooks(
            listOf(
                book("half", "Half"), // 500/1000 = 50%
                book("done", "Done"), // 900/1000 = 90%
                book("fresh", "Fresh"), // no entry → 0%
                book("quarter", "Quarter"), // 250/1000 = 25%
            ),
            BookSortKey.Progress,
            progressByBookId = mapOf("half" to 500L, "done" to 900L, "quarter" to 250L),
        )
        assertEquals(listOf("Done", "Half", "Quarter", "Fresh"), out.map { it.title })
    }

    @Test
    fun `sectionStartsFor Progress buckets by percent finished`() {
        val out = sectionStartsFor(
            listOf(
                book("done", "Done"), // 100%
                book("half", "Half"), // 50%
                book("fresh", "Fresh"), // 0%
            ),
            BookSortKey.Progress,
            progressByBookId = mapOf("done" to 1000L, "half" to 500L),
        )
        assertEquals(listOf(0 to "100%", 1 to "50%", 2 to "0%"), out)
    }

    @Test
    fun `sectionStartsFor Title returns first-letter starts`() {
        val out = sectionStartsFor(
            listOf(
                book("a", "Alpha"),
                book("a2", "Apple"),
                book("b", "Bravo"),
                book("4", "4chan"),
            ),
            BookSortKey.Title,
        )
        assertEquals(listOf(0 to "A", 2 to "B", 3 to "#"), out)
    }

    @Test
    fun `sectionStartsFor Author labels unknown-author as question mark`() {
        val out = sectionStartsFor(
            listOf(
                book("a", "A", author = "Asimov"),
                book("b", "B", author = null),
                book("c", "C", author = null),
            ),
            BookSortKey.Author,
        )
        assertEquals(listOf(0 to "A", 1 to "?"), out)
    }

    @Test
    fun `sectionStartsFor Recent buckets by age relative to nowMs`() {
        val now = 100L * 24 * 60 * 60 * 1000 // arbitrary "now"
        val oneDay = 24L * 60 * 60 * 1000
        val out = sectionStartsFor(
            listOf(
                book("today", "T", lastPlayedAt = now - oneDay / 2),
                book("week", "W", lastPlayedAt = now - 3 * oneDay),
                book("month", "M", lastPlayedAt = now - 14 * oneDay),
                book("older", "O", lastPlayedAt = now - 60 * oneDay),
                book("never", "N", lastPlayedAt = null),
            ),
            BookSortKey.Recent,
            nowMs = now,
        )
        assertEquals(
            listOf(0 to "Today", 1 to "Week", 2 to "Month", 3 to "Older", 4 to "Never"),
            out,
        )
    }

    @Test
    fun `sectionStartsFor empty list returns empty`() {
        assertEquals(emptyList<Pair<Int, String>>(), sectionStartsFor(emptyList(), BookSortKey.Title))
    }

    @Test
    fun `filterBooks All returns input unchanged`() {
        val input = listOf(book("a", "A"), book("b", "B", lastPlayedAt = 1L))
        assertEquals(input, filterBooks(input, BookFilter.All))
    }

    @Test
    fun `filterBooks Current keeps only books with lastPlayedAt`() {
        val out = filterBooks(
            listOf(
                book("a", "A", lastPlayedAt = null),
                book("b", "B", lastPlayedAt = 100L),
                book("c", "C", lastPlayedAt = null),
            ),
            BookFilter.Current,
        )
        assertEquals(listOf("B"), out.map { it.title })
    }

    @Test
    fun `filterBooks NotStarted keeps null lastPlayedAt AND null completedAt`() {
        val out = filterBooks(
            listOf(
                book("a", "A", lastPlayedAt = null),
                book("b", "B", lastPlayedAt = 100L),
                book("c", "C", lastPlayedAt = null, completedAt = 50L),
                book("d", "D", lastPlayedAt = null),
            ),
            BookFilter.NotStarted,
        )
        // Books C is "completed but never played" — semantically not "not started"
        assertEquals(listOf("A", "D"), out.map { it.title })
    }

    @Test
    fun `filterBooks Current excludes completed books even if lastPlayedAt set`() {
        val out = filterBooks(
            listOf(
                book("a", "A", lastPlayedAt = 100L),
                book("b", "B", lastPlayedAt = 200L, completedAt = 300L),
                book("c", "C", lastPlayedAt = null),
            ),
            BookFilter.Current,
        )
        assertEquals(listOf("A"), out.map { it.title })
    }

    @Test
    fun `filterBooks Completed keeps only books with non-null completedAt`() {
        val out = filterBooks(
            listOf(
                book("a", "A", lastPlayedAt = 100L),
                book("b", "B", completedAt = 200L),
                book("c", "C", lastPlayedAt = 50L, completedAt = 300L),
            ),
            BookFilter.Completed,
        )
        assertEquals(listOf("B", "C"), out.map { it.title })
    }

    @Test
    fun `searchBooks empty query returns input unchanged`() {
        val input = listOf(book("a", "A"), book("b", "B"))
        assertEquals(input, searchBooks(input, ""))
        assertEquals(input, searchBooks(input, "   "))
    }

    @Test
    fun `searchBooks matches title substring case-insensitive`() {
        val out = searchBooks(
            listOf(
                book("a", "The Hobbit"),
                book("b", "Lord of the Rings"),
                book("c", "Foundation"),
            ),
            "the",
        )
        assertEquals(listOf("The Hobbit", "Lord of the Rings"), out.map { it.title })
    }

    @Test
    fun `searchBooks matches author substring`() {
        val out = searchBooks(
            listOf(
                book("a", "A", author = "Asimov"),
                book("b", "B", author = "Bradbury"),
                book("c", "C", author = null),
            ),
            "brad",
        )
        assertEquals(listOf("B"), out.map { it.title })
    }

    @Test
    fun `searchBooks trims query whitespace`() {
        val out = searchBooks(
            listOf(book("a", "Alpha"), book("b", "Bravo")),
            "  alpha  ",
        )
        assertEquals(listOf("Alpha"), out.map { it.title })
    }
}
