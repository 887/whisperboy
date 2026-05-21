package com.eight87.whisperboy.ui.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.data.library.BookmarkSource
import com.eight87.whisperboy.data.library.ChapterSource
import com.eight87.whisperboy.data.playback.PlaybackSettings
import com.eight87.whisperboy.playback.NowPlayingState
import com.eight87.whisperboy.playback.PlaybackUiState
import com.eight87.whisperboy.playback.SleepTimerCommands
import com.eight87.whisperboy.playback.TransportCommands
import com.eight87.whisperboy.ui.library.NowPlayingBar
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * Overlay sheet that hosts the mini now-playing bar (peek) and the full
 * [PlaybackScreen] (expanded), cross-fading between them as a shared
 * [Animatable] runs 0 (collapsed) → 1 (expanded). Modeled on tonearmboy's
 * `TonearmboyApp` Auxio-style overlay: the sheet is a bottom-anchored Box
 * whose height grows from `peekDp` to the full screen, not a translation
 * — keeps the mini-player's `pointerInput` drag handler in-tree the whole
 * time and avoids canceling the gesture mid-drag.
 *
 * Cold-start-perf notes:
 *  - mini-player (peek) is ALWAYS composed when there's something to
 *    play, because its `pointerInput` owns the open-drag gesture and
 *    dropping it mid-drag cancels the gesture.
 *  - the full [PlaybackScreen] is **lazy-mounted** with `progress > 0.45f`
 *    (cold-start-perf A.5) so the heavy player chrome (cover load,
 *    transport row, slider, lifecycle Flows) stays out of the first frame.
 *  - screen height comes from `LocalConfiguration.current.screenHeightDp`,
 *    NOT `BoxWithConstraints` (cold-start-perf B.3 — subcompose round-trip
 *    on cold start is the exact thing we're avoiding).
 *  - the fast-ticking `sheetProgress.value` read stays inside this host's
 *    `Box` so library / parent recomposition is not triggered on drag.
 *
 * The host returns immediately (composes nothing) when there's no
 * [PlaybackUiState.Loaded] — no peek, no body, no reserved layout space.
 */
@Composable
fun NowPlayingSheet(
    nowPlayingState: NowPlayingState,
    transportCommands: TransportCommands,
    chapterSource: ChapterSource,
    bookmarkSource: BookmarkSource,
    playbackSettings: PlaybackSettings,
    sleepTimerCommands: SleepTimerCommands,
    sheetProgress: Animatable<Float, AnimationVector1D>,
    onCollapse: () -> Unit,
    onViewBookmarksClick: (bookId: String) -> Unit,
    peekDp: androidx.compose.ui.unit.Dp = DEFAULT_PEEK_DP,
    modifier: Modifier = Modifier,
) {
    val state by nowPlayingState.state.collectAsStateWithLifecycle()
    val showSheet = state is PlaybackUiState.Loaded
    if (!showSheet) return

    // Subscribe to the configured rewind / forward seconds here (sheet/app
    // level) so the mini-player stays stateless. Used only for the TalkBack
    // contentDescription on the rewind/forward buttons — without these the
    // CD reads "Rewind 0 seconds" / "Forward 0 seconds".
    val rewindSeconds by playbackSettings.rewindSeconds.collectAsStateWithLifecycle(initialValue = 30)
    val forwardSeconds by playbackSettings.forwardSeconds.collectAsStateWithLifecycle(initialValue = 30)

    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    val screenHeightPx = with(density) { screenHeightDp.toPx() }.coerceAtLeast(1f)
    val peekPx = with(density) { peekDp.toPx() }
    val dragRangePx = (screenHeightPx - peekPx).coerceAtLeast(1f)

    val scope = rememberCoroutineScope()
    val dragStartProgress = remember { mutableStateOf<Float?>(null) }

    val onSheetDragDelta: (Float) -> Unit = { delta ->
        scope.launch {
            if (dragStartProgress.value == null) {
                dragStartProgress.value = sheetProgress.value
            }
            // delta < 0 (finger up) → progress increases (sheet rises).
            val next = (sheetProgress.value - delta / dragRangePx).coerceIn(0f, 1f)
            sheetProgress.snapTo(next)
        }
    }
    val onSheetDragSettle: () -> Unit = {
        scope.launch {
            val start = dragStartProgress.value ?: 0f
            val end = sheetProgress.value
            val moved = end - start
            val flickThreshold = 0.05f
            val target = when {
                moved > flickThreshold -> 1f
                moved < -flickThreshold -> 0f
                else -> if (end >= 0.5f) 1f else 0f
            }
            sheetProgress.animateTo(target)
            dragStartProgress.value = null
        }
    }

    val progress = sheetProgress.value
    // Auxio-style staggered crossfade: mini 0..0.5, full 0.5..1.
    // C.2: alpha is read inside `graphicsLayer { ... }` lambdas below so the
    // sheetProgress State read defers to the draw phase, skipping recomposition.
    val miniAlpha: () -> Float = { (1f - min(sheetProgress.value * 2f, 1f)).coerceIn(0f, 1f) }
    val fullAlpha: () -> Float = { (max(sheetProgress.value - 0.5f, 0f) * 2f).coerceIn(0f, 1f) }

    val sheetHeightPx = peekPx + progress * dragRangePx
    val sheetHeightDp = with(density) { sheetHeightPx.toDp() }

    // Parent-level drag handler. Catches drags on areas NOT claimed by
    // descendants — same Auxio pattern tonearmboy uses. The mini-player
    // forwards its own drags via the explicit `pointerInput` it owns
    // (see [NowPlayingBar.onSheetDragDelta]).
    val sheetDraggable = rememberDraggableState { delta -> onSheetDragDelta(delta) }

    val sheetBackgroundActive = com.eight87.whisperboy.theme.LocalAlbumArtBackgroundActive.current
    val sheetCoverUri = com.eight87.whisperboy.theme.LocalPlayingCoverUri.current
    val sheetShowCoverWallpaper = sheetBackgroundActive && !sheetCoverUri.isNullOrBlank()
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeightDp)
                .then(
                    if (sheetShowCoverWallpaper) Modifier
                    else Modifier.background(MaterialTheme.colorScheme.surface),
                )
                .clipToBounds()
                .draggable(
                    state = sheetDraggable,
                    orientation = Orientation.Vertical,
                    onDragStopped = { onSheetDragSettle() },
                ),
        ) {
            // When the cover-wallpaper mode is active, paint a sheet-
            // internal blurred cover so the sheet's translucent chrome
            // doesn't reveal the library beneath. Mirrors tonearmboy.
            if (sheetShowCoverWallpaper) {
                com.eight87.whisperboy.theme.AlbumArtBackground(
                    coverUri = sheetCoverUri,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Inner stack anchored to the TOP of the sheet at fixed
            // full-screen height — keeps the layout from reflowing as
            // the sheet height grows. Cross-faded alphas hide / reveal.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(screenHeightDp),
            ) {
                // Full player (back of stack). Lazy-mount: only compose
                // when progress crosses the cross-fade threshold —
                // PlaybackScreen mounts its own LaunchedEffects /
                // Flows / cover load, all deferred off the first frame.
                if (progress > 0.45f) {
                    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fullAlpha() }) {
                        PlaybackScreen(
                            state = nowPlayingState,
                            transport = transportCommands,
                            chapterSource = chapterSource,
                            bookmarkSource = bookmarkSource,
                            playbackSettings = playbackSettings,
                            sleepTimerCommands = sleepTimerCommands,
                            onBack = onCollapse,
                            onViewBookmarksClick = onViewBookmarksClick,
                        )
                    }
                }
                // Mini-player (front of stack). ALWAYS composed —
                // dropping it mid-drag would cancel the gesture.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(peekDp)
                        .graphicsLayer { alpha = miniAlpha() },
                ) {
                    NowPlayingBar(
                        nowPlayingState = nowPlayingState,
                        transport = transportCommands,
                        onExpand = { scope.launch { sheetProgress.animateTo(1f) } },
                        rewindSeconds = rewindSeconds,
                        forwardSeconds = forwardSeconds,
                        onSheetDragDelta = onSheetDragDelta,
                        onSheetDragSettle = onSheetDragSettle,
                    )
                }
            }
        }
    }
    // NOTE: nested-scroll connection from the inline chapter queue inside
    // PlaybackScreen → sheet (Auxio "pull-down to collapse" once the
    // chapter list is at the top) is intentionally deferred. PlaybackScreen
    // exposes the queue's `LazyListState` via its `chapterListState`
    // parameter so a host that wants to wire a `NestedScrollConnection`
    // against it has a hook. Users collapse by dragging the cover /
    // transport area (caught by the parent `.draggable` above) or via
    // system back (BackHandler in WhisperboyApp).
}

/**
 * Peek height for the mini-player slot. Three-row layout — info row (~60dp:
 * 48dp cover + 6dp vertical padding ×2), transport row (~48dp default
 * IconButton target), and a 2dp LinearProgressIndicator pinned to the
 * bottom. At 80dp the third row was being clipped off, hiding the progress
 * strip. 118dp matches tonearmboy's MiniPlayer for the same row shape.
 */
private val DEFAULT_PEEK_DP = 118.dp
