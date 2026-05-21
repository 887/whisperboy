package com.eight87.whisperboy.data.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.eight87.whisperboy.data.settings.EnumSetting
import com.eight87.whisperboy.data.settings.Setting
import com.eight87.whisperboy.data.settings.enumSetting
import com.eight87.whisperboy.data.settings.setting
import kotlinx.coroutines.flow.Flow

/**
 * Narrow facet for the user's theme preferences (Phase K.5 — mirrors the
 * `LibraryUiSettings` shape: two `Flow<X>` properties + two `suspend setX(...)`
 * setters, no leaking DataStore).
 *
 * Backed by a `theme_settings` DataStore-Preferences file in
 * [AndroidThemeSettings]. Defaults: [ThemeMode.FollowSystem] and
 * `dynamicColor = true`.
 *
 * Two additional knobs — ported from tonearmboy's D.25.1 color-picker work —
 * sit alongside the mode/dynamic-color flat axes:
 *
 *  - [customBaseSeed]: 24-bit RGB packed into a Long. `0L` = unset (the
 *    default), in which case the dynamic-color / static-fallback path
 *    decides the foundation `ColorScheme`. Non-zero values override
 *    Material You and the static fallback both: the user has explicitly
 *    asked for "this colour" as the Material 3 seed and we honour it.
 *  - [customChromeTint]: 24-bit RGB packed into a Long. `0L` = unset
 *    (the default), in which case the F.6 Palette-from-cover gradient
 *    on `PlaybackScreen` keeps its cover-derived tint. Non-zero values
 *    override that tint app-wide for surfaces that consume it.
 *
 * `0L` is chosen as the unset sentinel because 0x000000 (pure black)
 * is a degenerate Material You seed (the derived palette collapses) —
 * users who want a near-black tint can pick `0x010101`, identical to
 * the eye but distinguishable from "unset". Same sentinel discipline
 * tonearmboy uses.
 */
interface ThemeSettings {
    val mode: Flow<ThemeMode>
    val dynamicColor: Flow<Boolean>
    val customBaseSeed: Flow<Long>
    val customChromeTint: Flow<Long>
    /**
     * Whether `PlaybackScreen`'s Palette-derived cover-art tint is
     * applied to surfaces. Default `true` — the pre-existing behaviour.
     * `customChromeTint` (when non-zero) wins over this flag.
     */
    val tintChromeByAlbumArt: Flow<Boolean>
    /**
     * Whether the playing book's cover paints fullscreen as a blurred
     * background behind the chrome (Harmony-style glass). Gated on
     * [tintChromeByAlbumArt]; with both on, the activity root renders
     * the cover as wallpaper and chrome surfaces become translucent
     * to let it bleed through.
     */
    val albumArtBackgroundEnabled: Flow<Boolean>

    suspend fun setMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setCustomBaseSeed(rgb: Long)
    suspend fun setCustomChromeTint(rgb: Long)
    suspend fun setTintChromeByAlbumArt(enabled: Boolean)
    suspend fun setAlbumArtBackgroundEnabled(enabled: Boolean)

    /**
     * One-shot upgrade migrations. Idempotent — guarded by version
     * marker keys so a second invocation is a cheap no-op. Currently:
     *
     *  - V2: force-on the album-art chrome tint + background pair AND
     *    clear `customChromeTint` (a pinned custom tint hard-overrides
     *    the album palette, so an upgrader with any colour parked
     *    there would otherwise see album-art tint silently no-op).
     */
    suspend fun runMigrations()
}

/**
 * DataStore-backed implementation. Stores [ThemeMode] as `enum.name`;
 * unknown / null values fall back to [ThemeMode.FollowSystem]. Dynamic-color
 * defaults to `true` (matches the pre-K.5 hard-coded behaviour in `Theme.kt`).
 *
 * R.B.2 migration: each knob delegates to the [Setting] / [EnumSetting] factories.
 */
class AndroidThemeSettings(
    private val dataStore: DataStore<Preferences>,
) : ThemeSettings {

    private val modeSetting: EnumSetting<ThemeMode> =
        dataStore.enumSetting("mode", ThemeMode.FollowSystem)
    private val dynamicColorSetting: Setting<Boolean> =
        dataStore.setting(booleanPreferencesKey("dynamic_color"), default = true)
    private val customBaseSeedSetting: Setting<Long> =
        dataStore.setting(longPreferencesKey("custom_base_seed"), default = 0L)
    private val customChromeTintSetting: Setting<Long> =
        dataStore.setting(longPreferencesKey("custom_chrome_tint"), default = 0L)
    private val tintChromeByAlbumArtSetting: Setting<Boolean> =
        dataStore.setting(booleanPreferencesKey("tint_chrome_by_album_art"), default = true)
    private val albumArtBackgroundEnabledSetting: Setting<Boolean> =
        dataStore.setting(booleanPreferencesKey("album_art_background_enabled"), default = true)

    override val mode: Flow<ThemeMode> = modeSetting.flow
    override val dynamicColor: Flow<Boolean> = dynamicColorSetting.flow
    override val customBaseSeed: Flow<Long> = customBaseSeedSetting.flow
    override val customChromeTint: Flow<Long> = customChromeTintSetting.flow
    override val tintChromeByAlbumArt: Flow<Boolean> = tintChromeByAlbumArtSetting.flow
    override val albumArtBackgroundEnabled: Flow<Boolean> = albumArtBackgroundEnabledSetting.flow

    override suspend fun setMode(mode: ThemeMode) = modeSetting.set(mode)
    override suspend fun setDynamicColor(enabled: Boolean) = dynamicColorSetting.set(enabled)
    override suspend fun setCustomBaseSeed(rgb: Long) = customBaseSeedSetting.set(rgb)
    override suspend fun setCustomChromeTint(rgb: Long) = customChromeTintSetting.set(rgb)
    override suspend fun setTintChromeByAlbumArt(enabled: Boolean) =
        tintChromeByAlbumArtSetting.set(enabled)
    override suspend fun setAlbumArtBackgroundEnabled(enabled: Boolean) =
        albumArtBackgroundEnabledSetting.set(enabled)

    override suspend fun runMigrations() {
        val v2Marker = booleanPreferencesKey("album_art_visuals_defaulted_v2")
        dataStore.edit { prefs ->
            if (prefs[v2Marker] != true) {
                prefs[booleanPreferencesKey("tint_chrome_by_album_art")] = true
                prefs[booleanPreferencesKey("album_art_background_enabled")] = true
                prefs[longPreferencesKey("custom_chrome_tint")] = 0L
                prefs[v2Marker] = true
            }
        }
    }
}
