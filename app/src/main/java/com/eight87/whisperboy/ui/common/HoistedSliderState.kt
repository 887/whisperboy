package com.eight87.whisperboy.ui.common

import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * A [SliderState] driven by a hoisted `value` — the state-based replacement for the
 * `Slider(value = ..., onValueChange = ...)` overload deprecated in material3 1.5.0.
 *
 * `rememberSliderState` is not a fit here: it is `rememberSaveable` with no keys, so it
 * keeps the first `trackRange` forever (the player scrubber's range changes on every
 * chapter). This keys the state on `steps` + `valueRange` and pushes the caller's
 * current `value` into it on each composition, so the caller stays the single source
 * of truth and `onValueChange` reports drags back to it.
 */
@Composable
fun rememberHoistedSliderState(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
): SliderState {
    val state = remember(steps, valueRange) {
        SliderState(value = value, steps = steps, trackRange = valueRange)
    }
    state.value = value
    return state
}
