package com.eight87.whisperboy.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.R
import com.eight87.whisperboy.theme.SleepTimerAccent
import com.eight87.whisperboy.playback.SleepTimerCommands
import com.eight87.whisperboy.playback.SleepTimerMode
import com.eight87.whisperboy.playback.SleepTimerState
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Phase G.2 — sleep timer bottom sheet.
 *
 * Quick-pick row (5 / 10 / 15 / 30 / 60 minutes) as [FilterChip]s, an "End of chapter" button,
 * a "Custom…" entry that opens a duration picker dialog, and (when a timer is active) a
 * "Cancel timer" footer button.
 *
 * Reads the active state from [SleepTimerCommands.state] via `collectAsStateWithLifecycle` so a
 * remote `arm()` from the future settings auto-arm window (G.6) reflects immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    sleepTimerCommands: SleepTimerCommands,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val state by sleepTimerCommands.state.collectAsStateWithLifecycle()
    var customOpen by remember { mutableStateOf(false) }

    // m3-expressive D.2 / D.3 — sleep-timer sheet pinned to the M3E `surfaceContainer`
    // tier with the sleep-category accent (indigo) painting both the drag handle and
    // the title so the sheet reads as a sleep surface end-to-end. Mirrors the
    // settings rows' coloured-avatar treatment one level up the chrome stack.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SleepTimerAccent.onContainer) },
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleLarge,
                color = SleepTimerAccent.onContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            // If active, show remaining time at the top.
            val running = state as? SleepTimerState.Running
            if (running != null) {
                val label = when {
                    running.mode is SleepTimerMode.EndOfChapter ->
                        stringResource(R.string.sleep_end_of_chapter)
                    running.remainingMs > 0L ->
                        stringResource(R.string.sleep_active_remaining, formatMmSs(running.remainingMs))
                    else -> stringResource(R.string.sleep_active_remaining, formatMmSs(0L))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Quick-pick chips. LazyRow so 5/10/15/30/60 stay on one line on narrow screens.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(QUICK_PICKS, key = { it }) { minutes ->
                    val activeMode = running?.mode
                    val isActive = activeMode is SleepTimerMode.Timed &&
                        activeMode.duration == minutes.minutes
                    FilterChip(
                        selected = isActive,
                        onClick = {
                            scope.launch {
                                sleepTimerCommands.arm(SleepTimerMode.Timed(minutes.minutes))
                                onDismiss()
                            }
                        },
                        label = { Text(text = quickLabel(minutes)) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // End-of-chapter + Custom row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val endOfChapterActive = running?.mode is SleepTimerMode.EndOfChapter
                FilterChip(
                    selected = endOfChapterActive,
                    onClick = {
                        scope.launch {
                            sleepTimerCommands.arm(SleepTimerMode.EndOfChapter)
                            onDismiss()
                        }
                    },
                    label = { Text(text = stringResource(R.string.sleep_end_of_chapter)) },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { customOpen = true },
                    modifier = Modifier.wrapContentWidth(),
                ) {
                    Text(text = stringResource(R.string.sleep_custom))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Cancel-timer footer, only when active.
            if (state !is SleepTimerState.Inactive) {
                Button(
                    onClick = {
                        scope.launch {
                            sleepTimerCommands.cancel()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.sleep_cancel_timer))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (customOpen) {
        CustomDurationDialog(
            onPick = { duration ->
                scope.launch {
                    sleepTimerCommands.arm(SleepTimerMode.Timed(duration))
                    customOpen = false
                    onDismiss()
                }
            },
            onDismiss = { customOpen = false },
        )
    }
}

@Composable
private fun CustomDurationDialog(
    onPick: (Duration) -> Unit,
    onDismiss: () -> Unit,
) {
    var raw by remember { mutableStateOf("45") }
    val parsed = raw.toIntOrNull()
    val valid = parsed != null && parsed in 1..720

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.sleep_custom)) },
        text = {
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it.filter { ch -> ch.isDigit() }.take(3) },
                label = { Text(text = stringResource(R.string.sleep_custom_minutes_label)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (parsed != null && valid) onPick(parsed.minutes) },
                enabled = valid,
            ) {
                Text(text = stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** Quick-pick labels — five hard-coded resource lookups per the strings.xml block. */
@Composable
private fun quickLabel(minutes: Int): String = when (minutes) {
    5 -> stringResource(R.string.sleep_quick_5_min)
    10 -> stringResource(R.string.sleep_quick_10_min)
    15 -> stringResource(R.string.sleep_quick_15_min)
    30 -> stringResource(R.string.sleep_quick_30_min)
    60 -> stringResource(R.string.sleep_quick_60_min)
    else -> "$minutes min"
}

private val QUICK_PICKS = listOf(5, 10, 15, 30, 60)
