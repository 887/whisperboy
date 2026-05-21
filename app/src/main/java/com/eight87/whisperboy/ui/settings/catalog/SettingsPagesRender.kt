package com.eight87.whisperboy.ui.settings.catalog

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.R
import com.eight87.whisperboy.data.library.BookSortKey
import com.eight87.whisperboy.data.library.GridMode
import com.eight87.whisperboy.data.library.LibraryRescanCoordinator
import com.eight87.whisperboy.data.library.LibraryScanFilterSettings
import com.eight87.whisperboy.data.library.LibraryUiSettings
import com.eight87.whisperboy.data.library.SupportedAudioFormats
import com.eight87.whisperboy.data.playback.PlaybackSettings
import com.eight87.whisperboy.data.playback.SleepTimerSettings
import com.eight87.whisperboy.data.theme.ThemeMode
import com.eight87.whisperboy.data.theme.ThemeSettings
import com.eight87.whisperboy.ui.settings.ColorPickerDialog
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * Bundle of all data facets the catalog binds to. Constructed once in
 * `SettingsScreen` and passed through to per-section renderers so
 * each section's binding lambdas stay short.
 */
data class SettingsBindings(
    val themeSettings: ThemeSettings,
    val playbackSettings: PlaybackSettings,
    val sleepTimerSettings: SleepTimerSettings,
    val libraryUiSettings: LibraryUiSettings,
    val libraryScanFilterSettings: LibraryScanFilterSettings,
    val libraryRescanCoordinator: LibraryRescanCoordinator,
    val onLibraryFolders: () -> Unit,
    val onAbout: () -> Unit,
    val onLicenses: () -> Unit,
    val snackbarHostState: SnackbarHostState,
)

private val SEEK_CHOICES: List<Int> = listOf(5, 10, 30, 60)
private val AUTO_REWIND_CHOICES: List<Int> = listOf(0, 3, 5, 10)
private val SLEEP_DURATION_CHOICES: List<Duration> = listOf(
    5.minutes, 10.minutes, 15.minutes, 30.minutes, 45.minutes, 60.minutes,
)

/**
 * Render all entries for [section] as rows inside a single
 * [SettingsCard]. The section header above the card uses
 * `MaterialTheme.colorScheme.primary` (the accent / orange) per the
 * tonearmboy reference.
 */
@Composable
fun RenderSection(section: Section, bindings: SettingsBindings) {
    val entries = SettingsCatalog.bySection(section)
    if (entries.isEmpty()) return
    SettingsCard(
        title = stringResource(sectionLabelRes(section)),
        modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
    ) {
        entries.forEachIndexed { index, entry ->
            RenderEntry(entry = entry, bindings = bindings)
            if (index < entries.size - 1) SettingsRowDivider()
        }
    }
}

/**
 * Dispatch on the entry id, rendering the right row variant +
 * trailing widget + dialog picker for each catalog entry.
 */
@Composable
private fun RenderEntry(entry: SettingsCatalogEntry, bindings: SettingsBindings) {
    when (entry.id) {
        SettingsCatalog.ID_THEME_MODE -> ThemeModeRow(entry, bindings.themeSettings)
        SettingsCatalog.ID_BASE_THEME -> BaseThemeRow(entry, bindings.themeSettings)
        SettingsCatalog.ID_TINT_BY_ALBUM_ART -> TintByAlbumArtRow(entry, bindings.themeSettings)
        SettingsCatalog.ID_ALBUM_ART_BACKGROUND -> AlbumArtBackgroundRow(entry, bindings.themeSettings)
        SettingsCatalog.ID_CUSTOM_CHROME_TINT -> CustomChromeTintRow(entry, bindings.themeSettings)

        SettingsCatalog.ID_DEFAULT_SPEED -> DefaultSpeedRow(entry, bindings.playbackSettings)
        SettingsCatalog.ID_DEFAULT_SKIP_SILENCE -> DefaultSkipSilenceRow(entry, bindings.playbackSettings)
        SettingsCatalog.ID_DEFAULT_GAIN_DB -> DefaultGainRow(entry, bindings.playbackSettings)
        SettingsCatalog.ID_REWIND_SECONDS -> RewindSecondsRow(entry, bindings.playbackSettings)
        SettingsCatalog.ID_FORWARD_SECONDS -> ForwardSecondsRow(entry, bindings.playbackSettings)
        SettingsCatalog.ID_AUTO_REWIND_SECONDS -> AutoRewindSecondsRow(entry, bindings.playbackSettings)
        SettingsCatalog.ID_SYSTEM_EQUALIZER -> SystemEqualizerRow(entry, bindings.snackbarHostState)

        SettingsCatalog.ID_SLEEP_DEFAULT_DURATION -> SleepDefaultDurationRow(entry, bindings.sleepTimerSettings)
        SettingsCatalog.ID_SLEEP_FADE_OUT -> SleepFadeOutRow(entry, bindings.sleepTimerSettings)
        SettingsCatalog.ID_SLEEP_SHAKE_TO_RESUME -> SleepShakeToResumeRow(entry, bindings.sleepTimerSettings)
        SettingsCatalog.ID_SLEEP_AUTO_ARM_WINDOW -> SleepAutoArmWindowRow(entry, bindings.sleepTimerSettings)

        SettingsCatalog.ID_LIBRARY_FOLDERS -> LibraryFoldersRow(entry, bindings.onLibraryFolders)
        SettingsCatalog.ID_LIBRARY_SORT -> LibrarySortRow(entry, bindings.libraryUiSettings)
        SettingsCatalog.ID_LIBRARY_GRID_MODE -> LibraryGridModeRow(entry, bindings.libraryUiSettings)
        SettingsCatalog.ID_LIBRARY_SCAN_FILTERS -> LibraryScanFiltersRow(
            entry,
            bindings.libraryScanFilterSettings,
            bindings.libraryRescanCoordinator,
            bindings.snackbarHostState,
        )
        SettingsCatalog.ID_LIBRARY_RESCAN -> LibraryRescanRow(entry, bindings.libraryRescanCoordinator)

        SettingsCatalog.ID_ABOUT -> AboutRow(entry, bindings.onAbout)
        SettingsCatalog.ID_LICENSES -> LicensesRow(entry, bindings.onLicenses)
    }
}

// region — Appearance

@Composable
private fun ThemeModeRow(entry: SettingsCatalogEntry, themeSettings: ThemeSettings) {
    val scope = rememberCoroutineScope()
    val mode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.FollowSystem)
    val picker = rememberSettingPickerState()
    val label = stringResource(themeModeLabelRes(mode))
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = label,
        onClick = picker::show,
    )
    val themeLabels = ThemeMode.entries.associateWith { stringResource(themeModeLabelRes(it)) }
    // User-requested ordering: "Follow system" first (the most common
    // pick + the persisted default), then explicit Light / Dark. Don't
    // touch the enum's declaration order — other code may rely on it.
    val themeOptions = listOf(ThemeMode.FollowSystem, ThemeMode.Light, ThemeMode.Dark)
    picker.RadioPicker(
        title = stringResource(entry.labelRes),
        options = themeOptions,
        label = { themeLabels[it].orEmpty() },
        current = mode,
        onPick = { scope.launch { themeSettings.setMode(it) } },
    )
}

private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.Light -> R.string.settings_theme_mode_light
    ThemeMode.Dark -> R.string.settings_theme_mode_dark
    ThemeMode.FollowSystem -> R.string.settings_theme_mode_system
}

/**
 * "Base theme" three-option picker: Dynamic / Static / Custom color.
 * Combines the legacy "Dynamic color" toggle + "Custom base color"
 * row into one entry (tonearmboy parity).
 *
 * Mapping to the underlying [ThemeSettings] knobs:
 *  - Dynamic → `dynamicColor = true`, `customBaseSeed = 0L`.
 *  - Static  → `dynamicColor = false`, `customBaseSeed = 0L`.
 *  - Custom  → opens [ColorPickerDialog]; on confirm sets
 *    `customBaseSeed = picked` (the existing render path in `Theme.kt`
 *    treats `customBaseSeed != 0` as the strongest override).
 */
@Composable
private fun BaseThemeRow(entry: SettingsCatalogEntry, themeSettings: ThemeSettings) {
    val scope = rememberCoroutineScope()
    val dynamicColor by themeSettings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
    val seed by themeSettings.customBaseSeed.collectAsStateWithLifecycle(initialValue = 0L)
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var pickerOpen by remember { mutableStateOf(false) }
    var colorPickerOpen by remember { mutableStateOf(false) }

    val dynamicLabel = stringResource(R.string.settings_catalog_base_theme_dynamic_option)
    val staticLabel = stringResource(R.string.settings_catalog_base_theme_static_option)
    val customLabel = stringResource(R.string.settings_catalog_base_theme_custom_option)
    val customFormat = stringResource(R.string.settings_catalog_base_theme_custom_format, seed)

    val subtitle = when {
        seed != 0L -> customFormat
        dynamicColor && supported -> dynamicLabel
        else -> staticLabel
    }

    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = subtitle,
        onClick = { pickerOpen = true },
        trailing = { ColorSwatch(seed) },
    )

    if (pickerOpen) {
        // Three-option picker dialog. Tapping Dynamic / Static commits
        // immediately and dismisses; tapping Custom dismisses this
        // dialog and opens the color picker.
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(stringResource(entry.labelRes)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    BaseThemeOption(
                        label = dynamicLabel,
                        selected = seed == 0L && dynamicColor && supported,
                        enabled = supported,
                        onPick = {
                            scope.launch {
                                themeSettings.setDynamicColor(true)
                                themeSettings.setCustomBaseSeed(0L)
                            }
                            pickerOpen = false
                        },
                    )
                    BaseThemeOption(
                        label = staticLabel,
                        selected = seed == 0L && (!dynamicColor || !supported),
                        enabled = true,
                        onPick = {
                            scope.launch {
                                themeSettings.setDynamicColor(false)
                                themeSettings.setCustomBaseSeed(0L)
                            }
                            pickerOpen = false
                        },
                    )
                    BaseThemeOption(
                        label = customLabel,
                        selected = seed != 0L,
                        enabled = true,
                        onPick = {
                            pickerOpen = false
                            colorPickerOpen = true
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(stringResource(R.string.settings_dialog_close))
                }
            },
        )
    }

    if (colorPickerOpen) {
        val initial = if (seed == 0L) 0x6750A4L else seed
        ColorPickerDialog(
            initialRgb = initial,
            onConfirm = {
                scope.launch { themeSettings.setCustomBaseSeed(it) }
                colorPickerOpen = false
            },
            onDismiss = { colorPickerOpen = false },
            onReset = {
                scope.launch { themeSettings.setCustomBaseSeed(0L) }
                colorPickerOpen = false
            },
        )
    }
}

@Composable
private fun BaseThemeOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.RadioButton,
                onClick = onPick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TintByAlbumArtRow(entry: SettingsCatalogEntry, themeSettings: ThemeSettings) {
    val scope = rememberCoroutineScope()
    val checked by themeSettings.tintChromeByAlbumArt.collectAsStateWithLifecycle(initialValue = true)
    SettingsToggleRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = entry.subtitleRes?.let { stringResource(it) },
        checked = checked,
        onCheckedChange = { scope.launch { themeSettings.setTintChromeByAlbumArt(it) } },
    )
}

@Composable
private fun AlbumArtBackgroundRow(entry: SettingsCatalogEntry, themeSettings: ThemeSettings) {
    val scope = rememberCoroutineScope()
    val checked by themeSettings.albumArtBackgroundEnabled.collectAsStateWithLifecycle(initialValue = true)
    SettingsToggleRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = entry.subtitleRes?.let { stringResource(it) },
        checked = checked,
        onCheckedChange = { scope.launch { themeSettings.setAlbumArtBackgroundEnabled(it) } },
    )
}

@Composable
private fun CustomChromeTintRow(entry: SettingsCatalogEntry, themeSettings: ThemeSettings) {
    val scope = rememberCoroutineScope()
    val tint by themeSettings.customChromeTint.collectAsStateWithLifecycle(initialValue = 0L)
    var pickerOpen by remember { mutableStateOf(false) }
    val subtitle = if (tint == 0L)
        stringResource(R.string.settings_theme_custom_tint_subtitle_unset)
    else stringResource(R.string.settings_theme_custom_tint_subtitle_set, tint)
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = subtitle,
        onClick = { pickerOpen = true },
        trailing = { ColorSwatch(tint) },
    )
    if (pickerOpen) {
        val initial = if (tint == 0L) 0x6464C8L else tint
        ColorPickerDialog(
            initialRgb = initial,
            onConfirm = {
                scope.launch { themeSettings.setCustomChromeTint(it) }
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
            onReset = {
                scope.launch { themeSettings.setCustomChromeTint(0L) }
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun ColorSwatch(rgb: Long) {
    if (rgb == 0L) return
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF000000L or rgb)),
    )
}

// endregion

// region — Behaviour

@Composable
private fun DefaultSpeedRow(entry: SettingsCatalogEntry, playbackSettings: PlaybackSettings) {
    val scope = rememberCoroutineScope()
    val speed by playbackSettings.defaultSpeed.collectAsStateWithLifecycle(initialValue = 1.0f)
    val picker = rememberSettingPickerState()
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(R.string.settings_playback_default_speed_format, speed),
        onClick = picker::show,
    )
    picker.SliderPicker(
        title = stringResource(entry.labelRes),
        initial = speed,
        valueRange = 0.5f..3.5f,
        steps = 59,
        format = { v -> "%.2fx".format(v) },
        onConfirm = { scope.launch { playbackSettings.setDefaultSpeed(snapSpeed(it)) } },
        onReset = { scope.launch { playbackSettings.setDefaultSpeed(1.0f) } },
    )
}

private fun snapSpeed(raw: Float): Float = Math.round(raw / 0.05f) * 0.05f
private fun snapGain(raw: Float): Float = Math.round(raw / 0.5f) * 0.5f

@Composable
private fun DefaultSkipSilenceRow(entry: SettingsCatalogEntry, playbackSettings: PlaybackSettings) {
    val scope = rememberCoroutineScope()
    val checked by playbackSettings.defaultSkipSilence.collectAsStateWithLifecycle(initialValue = false)
    SettingsToggleRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(R.string.settings_catalog_default_skip_silence_subtitle),
        checked = checked,
        onCheckedChange = { scope.launch { playbackSettings.setDefaultSkipSilence(it) } },
    )
}

@Composable
private fun DefaultGainRow(entry: SettingsCatalogEntry, playbackSettings: PlaybackSettings) {
    val scope = rememberCoroutineScope()
    val gain by playbackSettings.defaultGainDb.collectAsStateWithLifecycle(initialValue = 0.0f)
    val picker = rememberSettingPickerState()
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(R.string.settings_playback_default_gain_format, gain),
        onClick = picker::show,
    )
    picker.SliderPicker(
        title = stringResource(entry.labelRes),
        initial = gain,
        valueRange = -3f..12f,
        steps = 29,
        format = { v -> "%+.1f dB".format(v) },
        onConfirm = { scope.launch { playbackSettings.setDefaultGainDb(snapGain(it)) } },
    )
}

@Composable
private fun RewindSecondsRow(entry: SettingsCatalogEntry, playbackSettings: PlaybackSettings) {
    val scope = rememberCoroutineScope()
    val seconds by playbackSettings.rewindSeconds.collectAsStateWithLifecycle(initialValue = 30)
    val picker = rememberSettingPickerState()
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(R.string.settings_playback_seconds_format, seconds),
        onClick = picker::show,
    )
    val seekLabels = SEEK_CHOICES.associateWith { stringResource(R.string.settings_playback_seconds_format, it) }
    picker.RadioPicker(
        title = stringResource(entry.labelRes),
        options = SEEK_CHOICES,
        label = { seekLabels[it].orEmpty() },
        current = seconds,
        onPick = { scope.launch { playbackSettings.setRewindSeconds(it) } },
    )
}

@Composable
private fun ForwardSecondsRow(entry: SettingsCatalogEntry, playbackSettings: PlaybackSettings) {
    val scope = rememberCoroutineScope()
    val seconds by playbackSettings.forwardSeconds.collectAsStateWithLifecycle(initialValue = 30)
    val picker = rememberSettingPickerState()
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(R.string.settings_playback_seconds_format, seconds),
        onClick = picker::show,
    )
    val seekLabels = SEEK_CHOICES.associateWith { stringResource(R.string.settings_playback_seconds_format, it) }
    picker.RadioPicker(
        title = stringResource(entry.labelRes),
        options = SEEK_CHOICES,
        label = { seekLabels[it].orEmpty() },
        current = seconds,
        onPick = { scope.launch { playbackSettings.setForwardSeconds(it) } },
    )
}

@Composable
private fun AutoRewindSecondsRow(entry: SettingsCatalogEntry, playbackSettings: PlaybackSettings) {
    val scope = rememberCoroutineScope()
    val seconds by playbackSettings.autoRewindSeconds.collectAsStateWithLifecycle(initialValue = 5)
    val picker = rememberSettingPickerState()
    val subtitle = if (seconds == 0)
        stringResource(R.string.settings_catalog_auto_rewind_off)
    else stringResource(R.string.settings_playback_seconds_format, seconds)
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = subtitle,
        onClick = picker::show,
    )
    val offLabel = stringResource(R.string.settings_catalog_auto_rewind_off)
    val autoRewindLabels = AUTO_REWIND_CHOICES.associateWith { v ->
        if (v == 0) offLabel
        else stringResource(R.string.settings_playback_seconds_format, v)
    }
    picker.RadioPicker(
        title = stringResource(entry.labelRes),
        options = AUTO_REWIND_CHOICES,
        label = { autoRewindLabels[it].orEmpty() },
        current = seconds,
        onPick = { scope.launch { playbackSettings.setAutoRewindSeconds(it) } },
    )
}

@Composable
private fun SystemEqualizerRow(entry: SettingsCatalogEntry, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.settings_playback_equalizer_unavailable)
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = entry.subtitleRes?.let { stringResource(it) },
        onClick = {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                scope.launch { snackbarHostState.showSnackbar(unavailable) }
            }
        },
    )
}

// endregion

// region — Sleep timer

@Composable
private fun SleepDefaultDurationRow(entry: SettingsCatalogEntry, sleepTimerSettings: SleepTimerSettings) {
    val scope = rememberCoroutineScope()
    val duration by sleepTimerSettings.defaultDuration.collectAsStateWithLifecycle(initialValue = 30.minutes)
    val picker = rememberSettingPickerState()
    val subtitle = stringResource(
        R.string.settings_sleep_duration_format,
        duration.inWholeMinutes.toInt(),
    )
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = subtitle,
        onClick = picker::show,
    )
    val durationLabels = SLEEP_DURATION_CHOICES.associateWith { d ->
        stringResource(R.string.settings_sleep_duration_format, d.inWholeMinutes.toInt())
    }
    picker.RadioPicker(
        title = stringResource(entry.labelRes),
        options = SLEEP_DURATION_CHOICES,
        label = { durationLabels[it].orEmpty() },
        current = duration,
        onPick = { scope.launch { sleepTimerSettings.setDefaultDuration(it) } },
    )
}

@Composable
private fun SleepFadeOutRow(entry: SettingsCatalogEntry, sleepTimerSettings: SleepTimerSettings) {
    val scope = rememberCoroutineScope()
    val fade by sleepTimerSettings.fadeOutDuration.collectAsStateWithLifecycle(initialValue = 30.seconds)
    val picker = rememberSettingPickerState()
    val seconds = fade.inWholeSeconds.toInt().coerceIn(0, 60)
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(R.string.settings_sleep_fade_out_format, seconds),
        onClick = picker::show,
    )
    val context = LocalContext.current
    picker.SliderPicker(
        title = stringResource(entry.labelRes),
        initial = seconds.toFloat(),
        valueRange = 0f..60f,
        steps = 59,
        format = { v -> context.getString(R.string.settings_sleep_fade_out_format, v.toInt()) },
        onConfirm = {
            scope.launch { sleepTimerSettings.setFadeOutDuration(it.toInt().coerceIn(0, 60).seconds) }
        },
    )
}

@Composable
private fun SleepShakeToResumeRow(entry: SettingsCatalogEntry, sleepTimerSettings: SleepTimerSettings) {
    val scope = rememberCoroutineScope()
    val checked by sleepTimerSettings.shakeToResume.collectAsStateWithLifecycle(initialValue = true)
    SettingsToggleRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(R.string.settings_catalog_sleep_shake_to_resume_subtitle),
        checked = checked,
        onCheckedChange = { scope.launch { sleepTimerSettings.setShakeToResume(it) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepAutoArmWindowRow(entry: SettingsCatalogEntry, sleepTimerSettings: SleepTimerSettings) {
    val scope = rememberCoroutineScope()
    val start by sleepTimerSettings.autoArmWindowStart.collectAsStateWithLifecycle(initialValue = null)
    val end by sleepTimerSettings.autoArmWindowEnd.collectAsStateWithLifecycle(initialValue = null)
    var editing by remember { mutableStateOf<WindowEdge?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }

    val subtitle = if (start == null && end == null)
        stringResource(R.string.settings_sleep_window_disabled)
    else "${start?.let { formatHm(it) } ?: "—"} – ${end?.let { formatHm(it) } ?: "—"}"

    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = subtitle,
        onClick = { dialogOpen = true },
    )

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(entry.labelRes)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = stringResource(R.string.settings_sleep_window_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WindowChip(
                            label = stringResource(R.string.settings_sleep_window_start_label),
                            time = start,
                            onClick = { editing = WindowEdge.Start },
                            modifier = Modifier.weight(1f),
                        )
                        WindowChip(
                            label = stringResource(R.string.settings_sleep_window_end_label),
                            time = end,
                            onClick = { editing = WindowEdge.End },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.settings_dialog_close))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { sleepTimerSettings.setAutoArmWindow(null, null) }
                    dialogOpen = false
                }) {
                    Text(stringResource(R.string.settings_sleep_window_clear))
                }
            },
        )
    }

    editing?.let { edge ->
        val seed = when (edge) {
            WindowEdge.Start -> start ?: LocalTime.of(22, 0)
            WindowEdge.End -> end ?: LocalTime.of(2, 0)
        }
        TimePickerDialogInline(
            initial = seed,
            onDismiss = { editing = null },
            onConfirm = { picked ->
                scope.launch {
                    when (edge) {
                        WindowEdge.Start -> sleepTimerSettings.setAutoArmWindow(picked, end)
                        WindowEdge.End -> sleepTimerSettings.setAutoArmWindow(start, picked)
                    }
                }
                editing = null
            },
        )
    }
}

private enum class WindowEdge { Start, End }

@Composable
private fun WindowChip(
    label: String,
    time: LocalTime?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                text = time?.let { formatHm(it) }
                    ?: stringResource(R.string.settings_sleep_window_disabled),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun formatHm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogInline(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.padding(24.dp)) {
                TimePicker(state = state)
                Spacer(Modifier.size(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                        Text(stringResource(R.string.dialog_ok))
                    }
                }
            }
        }
    }
}

// endregion

// region — Library

@Composable
private fun LibraryFoldersRow(entry: SettingsCatalogEntry, onClick: () -> Unit) {
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = entry.subtitleRes?.let { stringResource(it) },
        onClick = onClick,
    )
}

@Composable
private fun LibrarySortRow(entry: SettingsCatalogEntry, libraryUiSettings: LibraryUiSettings) {
    val scope = rememberCoroutineScope()
    val current by libraryUiSettings.sortKey.collectAsStateWithLifecycle(initialValue = BookSortKey.Title)
    val picker = rememberSettingPickerState()
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(sortKeyLabelRes(current)),
        onClick = picker::show,
    )
    val sortLabels = BookSortKey.entries.associateWith { stringResource(sortKeyLabelRes(it)) }
    picker.RadioPicker(
        title = stringResource(entry.labelRes),
        options = BookSortKey.entries,
        label = { sortLabels[it].orEmpty() },
        current = current,
        onPick = { scope.launch { libraryUiSettings.setSortKey(it) } },
    )
}

private fun sortKeyLabelRes(key: BookSortKey): Int = when (key) {
    BookSortKey.Recent -> R.string.settings_library_sort_recent
    BookSortKey.Title -> R.string.settings_library_sort_title
    BookSortKey.Author -> R.string.settings_library_sort_author
}

@Composable
private fun LibraryGridModeRow(entry: SettingsCatalogEntry, libraryUiSettings: LibraryUiSettings) {
    val scope = rememberCoroutineScope()
    val current by libraryUiSettings.gridMode.collectAsStateWithLifecycle(initialValue = GridMode.Grid)
    val picker = rememberSettingPickerState()
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = stringResource(gridModeLabelRes(current)),
        onClick = picker::show,
    )
    val gridLabels = GridMode.entries.associateWith { stringResource(gridModeLabelRes(it)) }
    picker.RadioPicker(
        title = stringResource(entry.labelRes),
        options = GridMode.entries,
        label = { gridLabels[it].orEmpty() },
        current = current,
        onPick = { scope.launch { libraryUiSettings.setGridMode(it) } },
    )
}

private fun gridModeLabelRes(mode: GridMode): Int = when (mode) {
    GridMode.Grid -> R.string.settings_library_grid_mode_grid
    GridMode.List -> R.string.settings_library_grid_mode_list
}

@Composable
private fun LibraryScanFiltersRow(
    entry: SettingsCatalogEntry,
    libraryScanFilterSettings: LibraryScanFilterSettings,
    libraryRescanCoordinator: LibraryRescanCoordinator,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val disabled by libraryScanFilterSettings.disabledExtensions.collectAsStateWithLifecycle(initialValue = emptySet())
    val allExtensions = remember { SupportedAudioFormats.extensions.sorted() }
    val picker = rememberSettingPickerState()
    val rescanMessage = stringResource(R.string.settings_library_scan_filters_rescan_snackbar)

    val enabledCount = allExtensions.size - disabled.size
    val subtitle = stringResource(R.string.settings_catalog_scan_filters_count, enabledCount, allExtensions.size)

    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = subtitle,
        onClick = picker::show,
    )

    // working set holds the ENABLED extensions; persisted set holds DISABLED.
    val enabledNow = allExtensions.filter { it !in disabled }.toSet()
    picker.MultiSelectPicker(
        title = stringResource(entry.labelRes),
        options = allExtensions,
        initialSelected = enabledNow,
        label = { ".$it" },
        onConfirm = { enabledChoice ->
            val newDisabled = allExtensions.filter { it !in enabledChoice }.toSet()
            scope.launch {
                libraryScanFilterSettings.setDisabledExtensions(newDisabled)
                libraryRescanCoordinator.requestRescan(force = true)
                snackbarHostState.showSnackbar(rescanMessage)
            }
        },
    )
}

@Composable
private fun LibraryRescanRow(entry: SettingsCatalogEntry, libraryRescanCoordinator: LibraryRescanCoordinator) {
    val state by libraryRescanCoordinator.state.collectAsStateWithLifecycle()
    val isRunning = state is com.eight87.whisperboy.data.library.RescanState.Running
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = entry.subtitleRes?.let { stringResource(it) },
        onClick = if (isRunning) null else {
            { libraryRescanCoordinator.requestRescan(force = true) }
        },
    )
}

// endregion

// region — About

@Composable
private fun AboutRow(entry: SettingsCatalogEntry, onClick: () -> Unit) {
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = entry.subtitleRes?.let { stringResource(it) },
        onClick = onClick,
    )
}

@Composable
private fun LicensesRow(entry: SettingsCatalogEntry, onClick: () -> Unit) {
    SettingsRow(
        id = entry.id,
        accent = entry.accent,
        icon = entry.icon,
        label = stringResource(entry.labelRes),
        subtitle = entry.subtitleRes?.let { stringResource(it) },
        onClick = onClick,
    )
}

// endregion
