package com.eight87.whisperboy.ui.routes

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.eight87.whisperboy.BookmarkRoute
import com.eight87.whisperboy.CoverSearchRoute
import com.eight87.whisperboy.ui.bookmark.BookmarkScreen
import com.eight87.whisperboy.ui.coverart.SelectCoverFromInternet
import kotlinx.coroutines.launch

/**
 * Routes that surface alongside the now-playing experience: the bookmark list
 * (tap-to-seek + auto-resume + open the sheet) and the per-book DuckDuckGo
 * cover-search screen.
 *
 * The full player itself (`PlaybackRoute`) is intentionally NOT registered:
 * the user-driven path through to the player goes via `NowPlayingSheet`
 * (peek → expand) overlay in `WhisperboyApp.kt`, not via a nav-stack push.
 * `PlaybackRoute` is retained in `NavigationKeys.kt` for future deeplinks
 * (notification tap, Auto launch) which will hand off to the sheet
 * `animateTo(1f)`.
 */
@Suppress("NOTHING_TO_INLINE") internal inline fun EntryProviderScope<NavKey>.registerPlaybackEntries(scope: RouteScope) {
    val graph = scope.graph
    val backStack = scope.backStack
    val coroutineScope = scope.coroutineScope

    entry<BookmarkRoute> { route ->
        // Phase H.1 — bookmark list for a single book. Tap on a row seeks the
        // active player to the bookmark, auto-resumes, and pops back so the user
        // lands inside the player resuming at the bookmark.
        BookmarkScreen(
            bookId = route.bookId,
            bookmarkSource = graph.bookmarkSource,
            chapterSource = graph.chapterSource,
            // The only entry point into BookmarkRoute is the view-bookmarks button on
            // the expanded player, which collapses the sheet on push so the destination
            // is visible. On back, re-expand the sheet so the user lands back inside
            // the player they came from (the alternative is dropping them on the
            // library, which is the bug being fixed here).
            onBack = {
                backStack.removeLastOrNull()
                scope.openSheet()
            },
            onBookmarkSeek = { positionInBookMs ->
                coroutineScope.launch {
                    graph.transportCommands.seekTo(positionInBookMs)
                    graph.transportCommands.play()
                }
                backStack.removeLastOrNull()
                // Make sure the player surface is on screen for the seek to be
                // visible — the sheet may have been collapsed when the user opened
                // the bookmark list from a future deeplink.
                scope.openSheet()
            },
            modifier = Modifier.safeDrawingPadding(),
        )
    }
    entry<CoverSearchRoute> { route ->
        // cover-art Phase B.7 — the per-book DuckDuckGo image-search surface.
        SelectCoverFromInternet(
            bookId = route.bookId,
            coverApi = graph.coverApi,
            bookSource = graph.bookSource,
            onClose = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}
