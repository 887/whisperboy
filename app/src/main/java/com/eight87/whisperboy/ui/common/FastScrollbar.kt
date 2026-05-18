package com.eight87.whisperboy.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * R.A.Q — draggable fast-scroll thumb for [LazyListState].
 *
 * Ported from sister-app tonearmboy (MIT → MIT, same author). See
 * `docs/plans/main.md` Phase E.1 and the tonearmboy commit lineage
 * `52d0b81` → `059ef25` → `92c2270` → `8979358` → `40da803` → `4e2ff73`
 * for the iteration history that produced the current shape.
 *
 * Overlays a slim thumb on the right edge of the parent
 * `BoxWithConstraints`, sized proportionally to viewport / total
 * content. Wider invisible hit-target wraps the visual thumb so a
 * fingertip can grab anywhere in [hitTargetWidth] pixels.
 *
 * Drag the thumb to fast-scroll: each vertical drag delta is
 * accumulated against the **start-of-drag** thumb position (captured
 * via `rememberUpdatedState`, so the lambda never sees a stale
 * value), giving a smooth drag-from-here gesture without jumping.
 *
 * Optional [sectionStarts] is a list of `(itemIndex, label)` pairs
 * marking where each section begins. When supplied, a small letter
 * chip is rendered on the track at each section's fractional Y
 * position — letting the user see all upcoming section boundaries
 * while scrolling.
 *
 * Hidden when the surface isn't scrollable.
 */
@Composable
fun FastScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 8.dp,
    thumbMinHeight: Dp = 56.dp,
    thumbColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
    hitTargetWidth: Dp = 40.dp,
    sectionStarts: List<Pair<Int, String>>? = null,
) {
    val totalItems by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visibleItems by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    val canScroll by remember {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }
    if (totalItems == 0 || !canScroll) return

    // Cache per-index measured size so the thumb position doesn't oscillate when items of very
    // different heights enter / leave the viewport (the merged-surface case in tonearmboy).
    val sizesByIndex = remember { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visible ->
            visible.forEach { sizesByIndex[it.index] = it.size }
        }
    }

    val firstIndex by remember { derivedStateOf { state.firstVisibleItemIndex } }
    val firstOffset by remember { derivedStateOf { state.firstVisibleItemScrollOffset } }
    val viewportPx by remember {
        derivedStateOf { state.layoutInfo.viewportSize.height.toFloat() }
    }
    val avgItemSizePx by remember {
        derivedStateOf {
            val items = state.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) 1f else items.map { it.size }.average().toFloat()
        }
    }

    ScrollbarThumb(
        modifier = modifier,
        totalItems = totalItems,
        firstIndex = firstIndex,
        firstOffsetPx = firstOffset.toFloat(),
        avgItemSizePx = avgItemSizePx,
        sizesByIndex = sizesByIndex,
        explicitViewportPx = viewportPx,
        isScrollInProgress = state.isScrollInProgress,
        thumbWidth = thumbWidth,
        thumbMinHeight = thumbMinHeight,
        thumbColor = thumbColor,
        hitTargetWidth = hitTargetWidth,
        sectionStarts = sectionStarts,
        onScrollToFraction = { fraction ->
            val maxIndex = (totalItems - visibleItems).coerceAtLeast(0)
            val targetIndex = (fraction * maxIndex).toInt().coerceIn(0, maxIndex)
            state.scrollToItem(targetIndex)
        },
    )
}

/** R.A.Q — same draggable thumb for [LazyGridState] (cover-grid library). */
@Composable
fun FastScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 8.dp,
    thumbMinHeight: Dp = 56.dp,
    thumbColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
    hitTargetWidth: Dp = 40.dp,
    sectionStarts: List<Pair<Int, String>>? = null,
) {
    val totalItems by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visibleItems by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    val canScroll by remember {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }
    if (totalItems == 0 || !canScroll) return

    val firstIndex by remember { derivedStateOf { state.firstVisibleItemIndex } }
    val firstOffset by remember { derivedStateOf { state.firstVisibleItemScrollOffset } }
    val avgItemSizePx by remember {
        derivedStateOf {
            val items = state.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) 1f else items.map { it.size.height }.average().toFloat()
        }
    }

    ScrollbarThumb(
        modifier = modifier,
        totalItems = totalItems,
        firstIndex = firstIndex,
        firstOffsetPx = firstOffset.toFloat(),
        avgItemSizePx = avgItemSizePx,
        isScrollInProgress = state.isScrollInProgress,
        thumbWidth = thumbWidth,
        thumbMinHeight = thumbMinHeight,
        thumbColor = thumbColor,
        hitTargetWidth = hitTargetWidth,
        sectionStarts = sectionStarts,
        onScrollToFraction = { fraction ->
            val maxIndex = (totalItems - visibleItems).coerceAtLeast(0)
            val targetIndex = (fraction * maxIndex).toInt().coerceIn(0, maxIndex)
            state.scrollToItem(targetIndex)
        },
    )
}

/** Linger window after a scroll stops — chips fade out [LINGER_MS] later. */
private const val LINGER_MS = 800L

@Composable
private fun ScrollbarThumb(
    modifier: Modifier,
    totalItems: Int,
    firstIndex: Int,
    firstOffsetPx: Float,
    avgItemSizePx: Float,
    sizesByIndex: Map<Int, Int> = emptyMap(),
    explicitViewportPx: Float? = null,
    isScrollInProgress: Boolean = false,
    thumbWidth: Dp,
    thumbMinHeight: Dp,
    thumbColor: Color,
    hitTargetWidth: Dp,
    sectionStarts: List<Pair<Int, String>>? = null,
    onScrollToFraction: suspend (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var dragTopPxOverride by remember { mutableStateOf<Float?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            lingering = true
        } else {
            delay(LINGER_MS)
            lingering = false
        }
    }
    val chipsVisible = isScrollInProgress || isDragging || lingering

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(hitTargetWidth)
            .semantics { testTag = "fast_scrollbar" },
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val itemSizeOf: (Int) -> Float = { idx ->
            sizesByIndex[idx]?.toFloat() ?: avgItemSizePx
        }
        val totalContentPx = (0 until totalItems).sumOf { itemSizeOf(it).toDouble() }.toFloat()
        val viewportPx = (explicitViewportPx ?: trackHeightPx).coerceAtLeast(1f)
        val maxScrollPx = (totalContentPx - viewportPx).coerceAtLeast(1f)
        val thumbHeightPx = (viewportPx / totalContentPx.coerceAtLeast(1f) * trackHeightPx)
            .coerceAtLeast(with(density) { thumbMinHeight.toPx() })
        val maxThumbTopPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)

        val derivedTopPx = run {
            val priorPx = (0 until firstIndex).sumOf { itemSizeOf(it).toDouble() }.toFloat()
            val currentScroll = priorPx + firstOffsetPx
            (currentScroll / maxScrollPx).coerceIn(0f, 1f) * maxThumbTopPx
        }
        val thumbTopPx = dragTopPxOverride ?: derivedTopPx
        val thumbHeightDp = with(density) { thumbHeightPx.toDp() }
        val thumbTopDp = with(density) { thumbTopPx.toDp() }

        val currentThumbTopPx by rememberUpdatedState(thumbTopPx)
        val currentMaxThumbTopPx by rememberUpdatedState(maxThumbTopPx)

        if (!sectionStarts.isNullOrEmpty() && totalContentPx > 0f) {
            sectionStarts.forEach { (idx, label) ->
                val priorPx = (0 until idx.coerceAtMost(totalItems))
                    .sumOf { itemSizeOf(it).toDouble() }.toFloat()
                val chipFraction = (priorPx / totalContentPx).coerceIn(0f, 1f)
                val chipYDp = with(density) { (chipFraction * trackHeightPx).toDp() }
                AnimatedVisibility(
                    visible = chipsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = chipYDp)
                        .padding(end = thumbWidth + 4.dp)
                        .semantics { testTag = "fast_scrollbar_section_$label" },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(y = thumbTopDp)
                .fillMaxWidth()
                .height(thumbHeightDp)
                .semantics { testTag = "fast_scrollbar_thumb" }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragTopPxOverride = currentThumbTopPx
                            isDragging = true
                        },
                        onDragEnd = {
                            dragTopPxOverride = null
                            isDragging = false
                        },
                        onDragCancel = {
                            dragTopPxOverride = null
                            isDragging = false
                        },
                        onVerticalDrag = { _, deltaY ->
                            val current = dragTopPxOverride ?: currentThumbTopPx
                            val maxTop = currentMaxThumbTopPx
                            val next = (current + deltaY).coerceIn(0f, maxTop)
                            dragTopPxOverride = next
                            val fraction = if (maxTop > 0f) next / maxTop else 0f
                            scope.launch { onScrollToFraction(fraction) }
                        },
                    )
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(thumbWidth / 2))
                    .background(thumbColor),
            )
        }
    }
}
