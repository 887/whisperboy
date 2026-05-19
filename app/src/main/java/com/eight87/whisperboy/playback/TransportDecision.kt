package com.eight87.whisperboy.playback

/**
 * Pure decision functions for the [PlaybackController] transport surface.
 *
 * The controller wraps a Media3 [androidx.media3.session.MediaController] whose getters /
 * setters can only run on the Main thread, which makes the *decision* logic (what to do
 * given (itemCount, targetedBookId, …)) painful to unit-test on the JVM. By splitting the
 * "what to do" out of the "do it" we get a testable surface (this file) and a thin Main-
 * thread dispatcher inside [PlaybackController] that just executes the decision.
 *
 * No suspending, no Android types, no controller references — pure data in, pure data out.
 */
internal sealed interface PlayAction {
    /** Active session: just resume the controller. */
    object Resume : PlayAction
    /** Primed-but-not-loaded session: lazy-materialise via [PlaybackController.playBook]. */
    data class BootBook(val bookId: String) : PlayAction
    /** No session at all (cold-start, no last-played book). Do nothing. */
    object Idle : PlayAction
}

internal sealed interface ChapterTapAction {
    /** Active session, SingleFile book: seek absolute position within the lone MediaItem. */
    data class SeekSingleFile(val positionInBookMs: Long) : ChapterTapAction
    /** Active session, MultiFile book: seek to mediaItem at [index]. */
    data class SeekMultiFile(val index: Int) : ChapterTapAction
    /** Primed-but-not-loaded: lazy-materialise via [PlaybackController.playBookFromPosition]. */
    data class BootBookFromPosition(val bookId: String, val positionInBookMs: Long) : ChapterTapAction
    /** Nothing to do — no session and no items. */
    object Idle : ChapterTapAction
}

/**
 * Decide what `play()` should do given the live state.
 *
 *  - `itemCount > 0` → controller has MediaItems; just `c.play()`. (Resume.)
 *  - `itemCount == 0 && targetedBookId != null` → primed by cold-start, or service
 *    was killed; lazy-materialise items via [PlayAction.BootBook].
 *  - `itemCount == 0 && targetedBookId == null` → nothing to play. (Idle.)
 */
internal fun decidePlay(itemCount: Int, targetedBookId: String?): PlayAction = when {
    itemCount > 0 -> PlayAction.Resume
    targetedBookId != null -> PlayAction.BootBook(targetedBookId)
    else -> PlayAction.Idle
}

/**
 * Decide what `playChapter(index, positionInBookMs)` should do.
 *
 *  - `itemCount == 0 && targetedBookId != null` → boot via [playBookFromPosition].
 *  - `itemCount == 1` → SingleFile dispatch (one MediaItem spans the whole book,
 *    chapter boundaries are absolute positions within that single item).
 *  - `itemCount >= 2` → MultiFile dispatch (one MediaItem per chapter file).
 *  - `itemCount == 0 && targetedBookId == null` → no-op.
 */
internal fun decideChapterTap(
    itemCount: Int,
    targetedBookId: String?,
    chapterIndex: Int,
    positionInBookMs: Long,
): ChapterTapAction = when {
    itemCount == 0 && targetedBookId != null ->
        ChapterTapAction.BootBookFromPosition(targetedBookId, positionInBookMs)
    itemCount == 1 ->
        ChapterTapAction.SeekSingleFile(positionInBookMs.coerceAtLeast(0L))
    itemCount >= 2 ->
        ChapterTapAction.SeekMultiFile(chapterIndex.coerceIn(0, itemCount - 1))
    else -> ChapterTapAction.Idle
}
