package com.eight87.whisperboy.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.R
import com.eight87.whisperboy.playback.NowPlayingState
import com.eight87.whisperboy.playback.PlaybackUiState
import com.eight87.whisperboy.playback.TransportCommands
import com.eight87.whisperboy.ui.common.CoverArt
import com.eight87.whisperboy.ui.playback.formatMmSs
import kotlinx.coroutines.launch

/**
 * Phase E.6 — slim now-playing bar pinned to the bottom.
 *
 * Used as the **peek** of [com.eight87.whisperboy.ui.playback.NowPlayingSheet]:
 * tapping anywhere outside the transport buttons calls [onExpand], which the
 * host animates into the full-screen player by driving `sheetProgress` 0 → 1.
 * Vertical drag gestures on the outer Row are forwarded to the host via
 * [onSheetDragDelta] / [onSheetDragSettle], so the user can drag the sheet open
 * with a finger from anywhere on the bar (Auxio-style).
 *
 * Visible only when [PlaybackUiState] is `Loaded`. When idle / not-yet-connected
 * the composable returns immediately so nothing reserves layout space.
 *
 * Layout (3-row Column, mirroring tonearmboy's MiniPlayer shape, audiobook-flavoured):
 *
 *   Row 1 (info):     [cover 48dp] [title/chapter, weight=1]
 *   Row 2 (transport): [Replay] [SkipPrev] [Play/Pause] [SkipNext] [Forward]    (SpaceEvenly, 40dp targets, 24dp icons; Play/Pause 28dp)
 *   Row 3 (progress): [position mm:ss] .... 2dp progress line .... [chapter-duration mm:ss]
 *
 * The 2dp progress line tracks `position / duration` across the whole book and
 * uses `Modifier.graphicsLayer { scaleX = fraction }` (cold-start-perf C.2) so
 * the fast-ticking position field doesn't trigger composition of the whole bar.
 *
 * The mm:ss text readout *does* re-render each tick (text content changes
 * second-by-second). It lives in a scoped child composable [PositionReadout]
 * so recomposition stays bounded — cover, title, chapter, and the three
 * transport buttons skip the recomp.
 */
@Composable
fun NowPlayingBar(
    nowPlayingState: NowPlayingState,
    transport: TransportCommands,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Configured rewind seconds (PlaybackSettings.rewindSeconds). Threaded in so the
     * mini-player's TalkBack contentDescription reads e.g. "Rewind 30 seconds" instead
     * of "Rewind 0 seconds". Default 30 keeps standalone callers / previews honest.
     */
    rewindSeconds: Int = 30,
    /** Configured forward seconds (PlaybackSettings.forwardSeconds). See [rewindSeconds]. */
    forwardSeconds: Int = 30,
    /**
     * Sheet-drag forwarder. Each vertical drag delta on the outer Row arrives
     * here as a Y-pixel offset (negative = swipe up). Default no-op keeps
     * standalone callers / preview / tests working without the sheet plumbing.
     */
    onSheetDragDelta: (Float) -> Unit = {},
    /** Fired when the drag gesture ends (release or cancel). */
    onSheetDragSettle: () -> Unit = {},
) {
    val state by nowPlayingState.state.collectAsStateWithLifecycle()
    val loaded = state as? PlaybackUiState.Loaded ?: return

    val scope = rememberCoroutineScope()
    val bookDurationMs = loaded.book.durationMs.coerceAtLeast(1L)
    val progressFraction = { (loaded.positionInBookMs.toFloat() / bookDurationMs).coerceIn(0f, 1f) }

    // Wrap in Surface (rather than `Modifier.background(surfaceContainerHigh)`)
    // so `LocalContentColor` propagates to the transport-row IconButtons —
    // Modifier.background paints a colour but doesn't set the content-color
    // CompositionLocal, so the icons inherit the OUTER (often near-black
    // against a tinted dark surface) colour and become invisible. Same
    // "missing frame" fix tonearmboy got.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Row 1 — info. Cover + title/chapter only. Drag + tap-to-expand gestures
        // live on this row (the transport row below has its own tap targets so a
        // tap on a button shouldn't also expand the sheet).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpand() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { onSheetDragSettle() },
                        onDragCancel = { onSheetDragSettle() },
                    ) { _, delta -> onSheetDragDelta(delta) }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                coverPath = loaded.book.coverPath,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = loaded.book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val chapter = loaded.currentChapter
                if (chapter != null) {
                    Text(
                        text = chapter.title
                            ?: stringResource(R.string.player_unknown_chapter, chapter.chapterIndex + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Row 2 — five-button transport row. Replay / SkipPrev / Play-Pause /
        // SkipNext / Forward, space-evenly. Rewind/Forward call rewind()/forward()
        // on the transport bridge; the configured seconds value is threaded in
        // (params [rewindSeconds] / [forwardSeconds]) purely for TalkBack content
        // descriptions ("Rewind 30 seconds" not "Rewind 0 seconds").
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { scope.launch { transport.rewind() } }) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = stringResource(R.string.player_rewind_cd, rewindSeconds),
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = { scope.launch { transport.prevChapter() } }) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.player_skip_prev_cd),
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = {
                    scope.launch {
                        if (loaded.isPlaying) transport.pause() else transport.play()
                    }
                },
            ) {
                Icon(
                    imageVector = if (loaded.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (loaded.isPlaying) R.string.player_pause_cd else R.string.player_play_cd,
                    ),
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = { scope.launch { transport.nextChapter() } }) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.player_skip_next_cd),
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = { scope.launch { transport.forward() } }) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = stringResource(R.string.player_forward_cd, forwardSeconds),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Full-width 2dp progress strip pinned to the bottom of the bar — matches
        // tonearmboy's MiniPlayer exactly (LinearProgressIndicator, no mm:ss labels).
        // The strip stays visible at 0% (track color renders even when progress=0)
        // and gives the user a glanceable position cue without the labels eating
        // a whole text row of vertical space.
        LinearProgressIndicator(
            progress = progressFraction,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
    }
}

