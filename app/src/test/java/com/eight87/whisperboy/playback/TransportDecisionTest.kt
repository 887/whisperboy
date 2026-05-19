package com.eight87.whisperboy.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the pure transport-decision functions [decidePlay] and
 * [decideChapterTap]. These power the `play()` / `playChapter()` paths in
 * [PlaybackController]; testing them at the decision layer catches the "play
 * button does nothing" regression without needing a live MediaController
 * (which can't be constructed off-device).
 */
class TransportDecisionTest {

    // ----- decidePlay -----

    @Test
    fun `play with active session resumes the controller`() {
        assertEquals(PlayAction.Resume, decidePlay(itemCount = 5, targetedBookId = "book42"))
    }

    @Test
    fun `play with active session and null targetedBookId still resumes`() {
        // mediaItemCount > 0 should be sufficient; the controller has work to do.
        assertEquals(PlayAction.Resume, decidePlay(itemCount = 1, targetedBookId = null))
    }

    @Test
    fun `play with primed targeted book but no items boots the book`() {
        assertEquals(
            PlayAction.BootBook("book42"),
            decidePlay(itemCount = 0, targetedBookId = "book42"),
        )
    }

    @Test
    fun `play with nothing targeted and no items is a no-op`() {
        assertEquals(PlayAction.Idle, decidePlay(itemCount = 0, targetedBookId = null))
    }

    // ----- decideChapterTap -----

    @Test
    fun `chapter tap on SingleFile book seeks absolute position`() {
        assertEquals(
            ChapterTapAction.SeekSingleFile(positionInBookMs = 420_000L),
            decideChapterTap(itemCount = 1, targetedBookId = "b", chapterIndex = 2, positionInBookMs = 420_000L),
        )
    }

    @Test
    fun `chapter tap on SingleFile clamps negative position to zero`() {
        assertEquals(
            ChapterTapAction.SeekSingleFile(positionInBookMs = 0L),
            decideChapterTap(itemCount = 1, targetedBookId = "b", chapterIndex = 0, positionInBookMs = -5L),
        )
    }

    @Test
    fun `chapter tap on MultiFile seeks by chapter index`() {
        assertEquals(
            ChapterTapAction.SeekMultiFile(index = 2),
            decideChapterTap(itemCount = 5, targetedBookId = "b", chapterIndex = 2, positionInBookMs = 0L),
        )
    }

    @Test
    fun `chapter tap on MultiFile clamps high index to last item`() {
        assertEquals(
            ChapterTapAction.SeekMultiFile(index = 4),
            decideChapterTap(itemCount = 5, targetedBookId = "b", chapterIndex = 99, positionInBookMs = 0L),
        )
    }

    @Test
    fun `chapter tap on MultiFile clamps negative index to zero`() {
        assertEquals(
            ChapterTapAction.SeekMultiFile(index = 0),
            decideChapterTap(itemCount = 5, targetedBookId = "b", chapterIndex = -1, positionInBookMs = 0L),
        )
    }

    @Test
    fun `chapter tap with no items but primed book boots from position`() {
        assertEquals(
            ChapterTapAction.BootBookFromPosition(bookId = "book42", positionInBookMs = 1_200_000L),
            decideChapterTap(itemCount = 0, targetedBookId = "book42", chapterIndex = 3, positionInBookMs = 1_200_000L),
        )
    }

    @Test
    fun `chapter tap with no items and no targeted book is idle`() {
        assertEquals(
            ChapterTapAction.Idle,
            decideChapterTap(itemCount = 0, targetedBookId = null, chapterIndex = 0, positionInBookMs = 0L),
        )
    }

    @Test
    fun `chapter tap with two items goes through MultiFile path`() {
        // Boundary: itemCount >= 2 is MultiFile (one MediaItem per chapter file).
        assertEquals(
            ChapterTapAction.SeekMultiFile(index = 1),
            decideChapterTap(itemCount = 2, targetedBookId = "b", chapterIndex = 1, positionInBookMs = 0L),
        )
    }
}
