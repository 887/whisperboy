package com.eight87.whisperboy.ui.settings.catalog

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.eight87.whisperboy.R
import com.eight87.whisperboy.theme.AboutAccent
import com.eight87.whisperboy.theme.CategoryAccent
import com.eight87.whisperboy.theme.LibraryAccent
import com.eight87.whisperboy.theme.LicensesAccent
import com.eight87.whisperboy.theme.PlaybackAccent
import com.eight87.whisperboy.theme.SleepTimerAccent
import com.eight87.whisperboy.theme.ThemeAccent

/**
 * Section identifies which grouped card the entry sits in on the
 * Settings root surface. Whisperboy's catalog flattens everything
 * onto one page (per the user's "tonearmboy shape" request); the
 * section enum drives the section-header text + grouped-card boundary.
 */
enum class Section {
    Appearance,
    Behaviour,
    SleepTimer,
    Library,
    About,
}

/** Translatable label for each section header. */
@StringRes
fun sectionLabelRes(section: Section): Int = when (section) {
    Section.Appearance -> R.string.settings_section_appearance
    Section.Behaviour -> R.string.settings_section_behaviour
    Section.SleepTimer -> R.string.settings_section_sleep_timer
    Section.Library -> R.string.settings_section_library
    Section.About -> R.string.settings_section_about
}

/**
 * Hand-picked default per-section accent for the row avatar tile.
 * Individual entries override via [SettingsCatalogEntry.accent] — tonearmboy
 * does per-entry accents to break the monochrome-per-section look.
 */
fun sectionAccent(section: Section): CategoryAccent = when (section) {
    Section.Appearance -> ThemeAccent
    Section.Behaviour -> PlaybackAccent
    Section.SleepTimer -> SleepTimerAccent
    Section.Library -> LibraryAccent
    Section.About -> AboutAccent
}

/**
 * One catalog entry. The catalog is the single source of truth for
 * every settings row visible at any settings surface. Search filters
 * this list; the page renderer iterates it.
 *
 * String resolution: `labelRes` / `subtitleRes` provide the translated
 * runtime strings for rendering; the canonical English `label` /
 * `subtitleEn` are kept inline so JVM-only Robolectric tests can
 * exercise catalog shape and the substring search without a Context.
 */
data class SettingsCatalogEntry(
    /** Stable id. Tests + bindings reference this. */
    val id: String,
    val section: Section,
    val icon: ImageVector,
    /** Canonical English label, used by [SettingsCatalog.search]. */
    val label: String,
    /** Canonical English subtitle hint, used by search. */
    val subtitleEn: String? = null,
    @StringRes val labelRes: Int,
    /** Optional static subtitle string resource (used when the row
     *  doesn't carry a dynamic current-value subtitle). */
    @StringRes val subtitleRes: Int? = null,
    val keywords: List<String> = emptyList(),
    /**
     * Per-entry accent overriding the section default. Set per row to
     * break the monochrome-per-section look (tonearmboy parity).
     */
    val accent: CategoryAccent? = null,
)

/**
 * Whisperboy's settings catalog. Every catalog entry shows up on the
 * Settings root surface, grouped by [Section] into rounded cards with
 * a `primary`-coloured section header above each card.
 *
 * The runtime UI (current values, trailing widgets, dialog pickers)
 * wires up in `SettingsPagesRender.kt`; this file is the entry list
 * + search index.
 */
object SettingsCatalog {

    // Stable IDs. Renaming any of these without updating the bindings
    // in `SettingsPagesRender.kt` + the tests is a compile-time-silent
    // bug, so they live as constants.
    const val ID_THEME_MODE = "appearance.theme_mode"
    /** Three-way picker: Dynamic / Static / Custom-seed. Subsumes the
     *  legacy `ID_DYNAMIC_COLOR` toggle + `ID_CUSTOM_BASE_SEED` row. */
    const val ID_BASE_THEME = "appearance.base_theme"
    /** Toggle for `PlaybackScreen`'s Palette-from-cover tint. */
    const val ID_TINT_BY_ALBUM_ART = "appearance.tint_by_album_art"
    const val ID_ALBUM_ART_BACKGROUND = "appearance.album_art_background"
    const val ID_CUSTOM_CHROME_TINT = "appearance.custom_chrome_tint"

    const val ID_DEFAULT_SPEED = "behaviour.default_speed"
    const val ID_DEFAULT_SKIP_SILENCE = "behaviour.default_skip_silence"
    const val ID_DEFAULT_GAIN_DB = "behaviour.default_gain_db"
    const val ID_REWIND_SECONDS = "behaviour.rewind_seconds"
    const val ID_FORWARD_SECONDS = "behaviour.forward_seconds"
    const val ID_AUTO_REWIND_SECONDS = "behaviour.auto_rewind_seconds"
    const val ID_SYSTEM_EQUALIZER = "behaviour.system_equalizer"

    const val ID_SLEEP_DEFAULT_DURATION = "sleep.default_duration"
    const val ID_SLEEP_FADE_OUT = "sleep.fade_out"
    const val ID_SLEEP_SHAKE_TO_RESUME = "sleep.shake_to_resume"
    const val ID_SLEEP_AUTO_ARM_WINDOW = "sleep.auto_arm_window"

    const val ID_LIBRARY_FOLDERS = "library.folders"
    const val ID_LIBRARY_SORT = "library.sort"
    const val ID_LIBRARY_GRID_MODE = "library.grid_mode"
    const val ID_LIBRARY_SCAN_FILTERS = "library.scan_filters"
    const val ID_LIBRARY_RESCAN = "library.rescan"

    const val ID_ABOUT = "about.about"
    const val ID_LICENSES = "about.licenses"

    /**
     * All entries in display order. The page renderer iterates this
     * list and groups by [Section]. Adding a setting means: one
     * entry here + one binding in `SettingsPagesRender.kt`.
     */
    val entries: List<SettingsCatalogEntry> = listOf(
        SettingsCatalogEntry(
            id = ID_THEME_MODE,
            section = Section.Appearance,
            icon = Icons.Filled.Palette,
            label = "Theme",
            subtitleEn = "Light, dark, or follow system",
            labelRes = R.string.settings_catalog_theme_mode_label,
            keywords = listOf("dark", "light", "system", "appearance"),
            accent = ThemeAccent,
        ),
        SettingsCatalogEntry(
            id = ID_BASE_THEME,
            section = Section.Appearance,
            icon = Icons.Filled.ColorLens,
            label = "Base theme",
            subtitleEn = "Foundation colors. Album art tint sits on top.",
            labelRes = R.string.settings_catalog_base_theme_label,
            subtitleRes = R.string.settings_catalog_base_theme_subtitle,
            keywords = listOf("dynamic", "material", "you", "static", "seed", "palette", "custom", "color"),
            accent = ThemeAccent,
        ),
        SettingsCatalogEntry(
            id = ID_TINT_BY_ALBUM_ART,
            section = Section.Appearance,
            icon = Icons.Filled.Palette,
            label = "Tint chrome by album art",
            subtitleEn = "Bias surfaces toward the playing book's dominant color.",
            labelRes = R.string.settings_catalog_tint_by_album_art_label,
            subtitleRes = R.string.settings_catalog_tint_by_album_art_subtitle,
            keywords = listOf("palette", "tint", "album", "cover", "art", "color"),
            accent = LibraryAccent,
        ),
        SettingsCatalogEntry(
            id = ID_ALBUM_ART_BACKGROUND,
            section = Section.Appearance,
            icon = Icons.Filled.Palette,
            label = "Use album art as background",
            subtitleEn = "Blur the playing cover behind the chrome (Harmony-style).",
            labelRes = R.string.settings_catalog_album_art_background_label,
            subtitleRes = R.string.settings_catalog_album_art_background_subtitle,
            keywords = listOf("background", "blur", "glass", "harmony", "cover", "wallpaper"),
            accent = LibraryAccent,
        ),
        SettingsCatalogEntry(
            id = ID_CUSTOM_CHROME_TINT,
            section = Section.Appearance,
            icon = Icons.Filled.Colorize,
            label = "Custom player tint",
            subtitleEn = "Override the album-art tint with a fixed colour.",
            labelRes = R.string.settings_catalog_custom_chrome_tint_label,
            keywords = listOf("tint", "player", "chrome", "custom"),
            accent = SleepTimerAccent,
        ),
        SettingsCatalogEntry(
            id = ID_DEFAULT_SPEED,
            section = Section.Behaviour,
            icon = Icons.Filled.Speed,
            label = "Default speed",
            subtitleEn = "Playback speed for new books",
            labelRes = R.string.settings_catalog_default_speed_label,
            keywords = listOf("speed", "playback"),
            accent = PlaybackAccent,
        ),
        SettingsCatalogEntry(
            id = ID_DEFAULT_SKIP_SILENCE,
            section = Section.Behaviour,
            icon = Icons.Filled.FastForward,
            label = "Skip silence",
            subtitleEn = "Skip quiet pauses during playback",
            labelRes = R.string.settings_catalog_default_skip_silence_label,
            subtitleRes = R.string.settings_catalog_default_skip_silence_subtitle,
            accent = PlaybackAccent,
        ),
        SettingsCatalogEntry(
            id = ID_DEFAULT_GAIN_DB,
            section = Section.Behaviour,
            icon = Icons.Filled.VolumeUp,
            label = "Default volume gain",
            subtitleEn = "Volume boost for new books in decibels",
            labelRes = R.string.settings_catalog_default_gain_label,
            keywords = listOf("gain", "boost", "decibel", "loud"),
            accent = PlaybackAccent,
        ),
        SettingsCatalogEntry(
            id = ID_REWIND_SECONDS,
            section = Section.Behaviour,
            icon = Icons.Filled.Replay,
            label = "Rewind seconds",
            subtitleEn = "How far back the rewind button skips",
            labelRes = R.string.settings_catalog_rewind_seconds_label,
            accent = PlaybackAccent,
        ),
        SettingsCatalogEntry(
            id = ID_FORWARD_SECONDS,
            section = Section.Behaviour,
            icon = Icons.Filled.Forward30,
            label = "Forward seconds",
            subtitleEn = "How far forward the forward button skips",
            labelRes = R.string.settings_catalog_forward_seconds_label,
            accent = PlaybackAccent,
        ),
        SettingsCatalogEntry(
            id = ID_AUTO_REWIND_SECONDS,
            section = Section.Behaviour,
            icon = Icons.Filled.History,
            label = "Auto-rewind on resume",
            subtitleEn = "Rewind when resuming after a long pause",
            labelRes = R.string.settings_catalog_auto_rewind_seconds_label,
            keywords = listOf("resume", "auto", "rewind"),
            accent = PlaybackAccent,
        ),
        SettingsCatalogEntry(
            id = ID_SYSTEM_EQUALIZER,
            section = Section.Behaviour,
            icon = Icons.Filled.GraphicEq,
            label = "System equalizer",
            subtitleEn = "Open the device equalizer",
            labelRes = R.string.settings_catalog_system_equalizer_label,
            subtitleRes = R.string.settings_catalog_system_equalizer_subtitle,
            keywords = listOf("eq", "audio"),
            accent = PlaybackAccent,
        ),
        SettingsCatalogEntry(
            id = ID_SLEEP_DEFAULT_DURATION,
            section = Section.SleepTimer,
            icon = Icons.Filled.Bedtime,
            label = "Default duration",
            subtitleEn = "Default sleep-timer duration",
            labelRes = R.string.settings_catalog_sleep_default_duration_label,
            accent = SleepTimerAccent,
        ),
        SettingsCatalogEntry(
            id = ID_SLEEP_FADE_OUT,
            section = Section.SleepTimer,
            icon = Icons.Filled.VolumeDown,
            label = "Fade-out",
            subtitleEn = "How long volume fades to silent before stop",
            labelRes = R.string.settings_catalog_sleep_fade_out_label,
            accent = SleepTimerAccent,
        ),
        SettingsCatalogEntry(
            id = ID_SLEEP_SHAKE_TO_RESUME,
            section = Section.SleepTimer,
            icon = Icons.Filled.Vibration,
            label = "Shake to resume",
            subtitleEn = "Pick up the phone to resume after sleep",
            labelRes = R.string.settings_catalog_sleep_shake_to_resume_label,
            subtitleRes = R.string.settings_catalog_sleep_shake_to_resume_subtitle,
            accent = SleepTimerAccent,
        ),
        SettingsCatalogEntry(
            id = ID_SLEEP_AUTO_ARM_WINDOW,
            section = Section.SleepTimer,
            icon = Icons.Filled.Schedule,
            label = "Auto-arm window",
            subtitleEn = "Arm the timer automatically in a time window",
            labelRes = R.string.settings_catalog_sleep_auto_arm_window_label,
            accent = SleepTimerAccent,
        ),
        SettingsCatalogEntry(
            id = ID_LIBRARY_FOLDERS,
            section = Section.Library,
            icon = Icons.Filled.FolderOpen,
            label = "Manage folders",
            subtitleEn = "Audiobook folders the library scans",
            labelRes = R.string.settings_catalog_library_folders_label,
            subtitleRes = R.string.settings_catalog_library_folders_subtitle,
            accent = LibraryAccent,
        ),
        SettingsCatalogEntry(
            id = ID_LIBRARY_SORT,
            section = Section.Library,
            icon = Icons.Filled.Sort,
            label = "Default sort",
            subtitleEn = "How books are ordered in the library",
            labelRes = R.string.settings_catalog_library_sort_label,
            accent = LibraryAccent,
        ),
        SettingsCatalogEntry(
            id = ID_LIBRARY_GRID_MODE,
            section = Section.Library,
            icon = Icons.Filled.GridView,
            label = "Default grid mode",
            subtitleEn = "Grid or list layout for the library",
            labelRes = R.string.settings_catalog_library_grid_mode_label,
            accent = LibraryAccent,
        ),
        SettingsCatalogEntry(
            id = ID_LIBRARY_SCAN_FILTERS,
            section = Section.Library,
            icon = Icons.Filled.FilterAlt,
            label = "Scan filters",
            subtitleEn = "Which audio file extensions to include in scans",
            labelRes = R.string.settings_catalog_library_scan_filters_label,
            accent = LibraryAccent,
        ),
        SettingsCatalogEntry(
            id = ID_LIBRARY_RESCAN,
            section = Section.Library,
            icon = Icons.Filled.Refresh,
            label = "Rescan now",
            subtitleEn = "Force a full library rescan",
            labelRes = R.string.settings_catalog_library_rescan_label,
            subtitleRes = R.string.settings_catalog_library_rescan_subtitle,
            accent = LibraryAccent,
        ),
        SettingsCatalogEntry(
            id = ID_ABOUT,
            section = Section.About,
            icon = Icons.Filled.Info,
            label = "About",
            subtitleEn = "Version, license, credits",
            labelRes = R.string.settings_catalog_about_label,
            subtitleRes = R.string.settings_catalog_about_subtitle,
            accent = AboutAccent,
        ),
        // Open-source licenses now lives inside the About sub-page (Source card).
        // Keeping a duplicate row here was a discoverability anti-pattern — one
        // canonical entry point per surface.
    )

    /** Look up by id; throws if missing — IDs are compile-time stable. */
    fun byId(id: String): SettingsCatalogEntry = entries.first { it.id == id }

    /** Entries for a section, in declaration order. */
    fun bySection(section: Section): List<SettingsCatalogEntry> =
        entries.filter { it.section == section }

    /**
     * Substring + keyword search. Case-insensitive match against
     * label, subtitleEn, and the explicit `keywords` list. Empty
     * query returns an empty list; the search overlay shows an
     * empty-state hint instead.
     */
    fun search(query: String): List<SettingsCatalogEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return entries.filter { entry ->
            entry.label.lowercase().contains(q) ||
                (entry.subtitleEn?.lowercase()?.contains(q) ?: false) ||
                entry.keywords.any { it.lowercase().contains(q) }
        }
    }
}
