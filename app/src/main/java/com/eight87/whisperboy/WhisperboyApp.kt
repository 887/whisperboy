package com.eight87.whisperboy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.eight87.whisperboy.playback.PlaybackUiState
import com.eight87.whisperboy.ui.playback.NowPlayingSheet
import com.eight87.whisperboy.ui.routes.RouteScope
import com.eight87.whisperboy.ui.routes.registerAllDestinations
import kotlinx.coroutines.launch

/**
 * Composition root.
 *
 * The now-playing surface (mini-player + full [com.eight87.whisperboy.ui.playback.PlaybackScreen])
 * is hosted as a single overlay **sheet** at this root via [NowPlayingSheet], NOT
 * pushed onto the back stack as a route. Tapping a book in the library fires
 * `playBook` and animates the sheet open 0 → 1; the mini-player is always
 * visible as a peek along the bottom whenever there's a Loaded playback state.
 * Modeled on tonearmboy's `TonearmboyApp` Auxio-style overlay.
 *
 * Sheet progress is owned here so the fast-ticking drag updates do not
 * propagate into the library's recomposition path (cold-start-perf C.1).
 *
 * **Phase L:** initial back-stack key is chosen from [com.eight87.whisperboy.data.onboarding.OnboardingSettings].completed:
 *
 * - `null` (DataStore hasn't emitted yet on this cold start): render nothing —
 *   the Android 12+ system splash stays up until the first emission lands.
 * - `false`: start at `OnboardingWelcomeRoute`. Completion replaces the stack with `HomeRoute`.
 * - `true`: start directly at `HomeRoute`. Existing users never see onboarding.
 *
 * **R.E.2 – R.E.4:** per-destination `entry<XRoute>` blocks live in
 * [com.eight87.whisperboy.ui.routes]; this file is just theme + scaffold +
 * a single `entryProvider { registerAllDestinations(scope) }` dispatch.
 */
@Composable
fun WhisperboyApp() {
    val graph = (LocalContext.current.applicationContext as WhisperboyApplication).graph

    val onboardingCompleted by graph.onboardingSettings.completed.collectAsStateWithLifecycle(initialValue = null)
    val initialKey: NavKey? = when (onboardingCompleted) {
        null -> null
        true -> HomeRoute
        false -> OnboardingWelcomeRoute
    }
    if (initialKey == null) return

    val backStack = rememberNavBackStack(initialKey)
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Sheet progress 0 (collapsed, mini-player only) → 1 (full player).
    val sheetProgress = remember { Animatable(0f) }
    val openSheet: () -> Unit = { coroutineScope.launch { sheetProgress.animateTo(1f) } }
    val closeSheet: () -> Unit = { coroutineScope.launch { sheetProgress.animateTo(0f) } }

    // Two-stage back: collapse the sheet first if it's open, otherwise the
    // NavDisplay's own onBack pops the back stack.
    BackHandler(enabled = sheetProgress.value > 0f) { closeSheet() }

    // When the mini-player peek is visible, pad the NavDisplay's bottom by the peek
    // height so library chrome (FAB, lists) doesn't sit underneath the peek bar.
    val playbackState by graph.nowPlayingState.state.collectAsStateWithLifecycle()
    val showMiniPlayer = playbackState is PlaybackUiState.Loaded
    val navDisplayBottomPad = if (showMiniPlayer) 80.dp else 0.dp

    // Replace the entire back stack with HomeRoute. Used by the onboarding
    // first-scan "Continue" button so back from Home doesn't re-enter the
    // onboarding flow.
    val finishOnboarding: () -> Unit = {
        backStack.clear()
        backStack.add(HomeRoute)
    }

    val routeScope = RouteScope(
        graph = graph,
        backStack = backStack,
        coroutineScope = coroutineScope,
        snackbar = snackbar,
        openSheet = openSheet,
        finishOnboarding = finishOnboarding,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(bottom = navDisplayBottomPad)) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryProvider = entryProvider { registerAllDestinations(routeScope) },
            )
        }

        // Overlay sheet — peek bar (mini-player) along the bottom, expands to
        // the full PlaybackScreen as `sheetProgress` runs 0 → 1.
        NowPlayingSheet(
            nowPlayingState = graph.nowPlayingState,
            transportCommands = graph.transportCommands,
            chapterSource = graph.chapterSource,
            bookmarkSource = graph.bookmarkSource,
            playbackSettings = graph.playbackSettings,
            sleepTimerCommands = graph.sleepTimerCommands,
            sheetProgress = sheetProgress,
            onCollapse = closeSheet,
            onViewBookmarksClick = { bookId ->
                // Collapse the sheet first — otherwise the fully-expanded player sits
                // on top of the BookmarkRoute destination and tapping the button
                // appears to "do nothing".
                closeSheet()
                backStack.add(BookmarkRoute(bookId))
            },
        )
    }
}
