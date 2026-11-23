package com.eight87.whisperboy.ui.settings.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import com.eight87.whisperboy.ui.common.rememberHoistedSliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eight87.whisperboy.R

/**
 * Ported from tonearmboy `SettingPicker.kt`. Bundles a radio-picker
 * dialog with its visibility state so the call site reads as one
 * declaration rather than a `var open by remember ...` scatter.
 *
 * Usage:
 * ```
 * val picker = rememberSettingPickerState()
 * SettingsRow(..., onClick = picker::show)
 * picker.RadioPicker(
 *   title = "Theme",
 *   options = ThemeMode.entries,
 *   label = { it.displayLabel },
 *   current = currentTheme,
 *   onPick = { scope.launch { themeSettings.setMode(it) } },
 * )
 * ```
 */
@Stable
class SettingPickerState {
    internal var visible by mutableStateOf(false)
    fun show() { visible = true }
    fun hide() { visible = false }
}

@Composable
fun rememberSettingPickerState(): SettingPickerState = remember { SettingPickerState() }

/**
 * Radio-picker dialog. Tapping a row both picks the value (firing
 * [onPick]) AND dismisses the dialog — single-tap commit, the
 * tonearmboy convention.
 */
@Composable
fun <T> SettingPickerState.RadioPicker(
    title: String,
    options: Iterable<T>,
    label: (T) -> String,
    current: T,
    onPick: (T) -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = ::hide,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == current,
                                role = Role.RadioButton,
                                onClick = {
                                    onPick(option)
                                    hide()
                                },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::hide) {
                Text(stringResource(R.string.settings_dialog_close))
            }
        },
    )
}

/**
 * Slider-picker dialog. Holds a local float so the dragging is smooth;
 * the actual persisted value writes on confirm only.
 */
@Composable
fun SettingPickerState.SliderPicker(
    title: String,
    initial: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onConfirm: (Float) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    if (!visible) return
    var draft by remember(initial) { mutableFloatStateOf(initial) }
    AlertDialog(
        onDismissRequest = ::hide,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = format(draft),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Slider(
                    state = rememberHoistedSliderState(
                        value = draft,
                        valueRange = valueRange,
                        steps = steps,
                    ),
                    onValueChange = { draft = it },
                )
                if (onReset != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        onReset()
                        hide()
                    }) {
                        Text(stringResource(R.string.settings_dialog_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(draft)
                hide()
            }) {
                Text(stringResource(R.string.settings_dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = ::hide) {
                Text(stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}

/**
 * Multi-select checkbox dialog used by Scan filters. Operates on a
 * mutable working set, persisting on confirm.
 */
@Composable
fun SettingPickerState.MultiSelectPicker(
    title: String,
    options: List<String>,
    initialSelected: Set<String>,
    label: (String) -> String,
    onConfirm: (Set<String>) -> Unit,
) {
    if (!visible) return
    var working by remember(initialSelected) { mutableStateOf(initialSelected) }
    AlertDialog(
        onDismissRequest = ::hide,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { option ->
                    val checked = option in working
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = checked,
                                role = Role.Checkbox,
                                onClick = {
                                    working = if (checked) working - option else working + option
                                },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(working)
                hide()
            }) {
                Text(stringResource(R.string.settings_dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = ::hide) {
                Text(stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}
