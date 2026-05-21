@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.eight87.whisperboy.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eight87.whisperboy.data.theme.ThemeMode
import com.eight87.whisperboy.data.theme.ThemeSettings
import com.eight87.whisperboy.playback.NowPlayingState

// m3-expressive A.4 / B.1 — fall through to the expressive seed
// schemes. They produce brighter container pairs (`primaryContainer` /
// `secondaryContainer` / `tertiaryContainer`) and the wider
// surface-tier ladder (`surfaceContainerLow…High`) the M3E look needs.
// `expressiveDarkColorScheme()` doesn't exist in 1.5.0-alpha18 — only
// the light variant ships — so we pair `expressiveLightColorScheme()`
// for light mode with `darkColorScheme()` plus the brand seeds for
// dark mode. Both still produce the full surface-tier ladder, which
// the surface-tier audit (Phase B) relies on.
internal val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

internal val LightColorScheme = expressiveLightColorScheme()

/**
 * CompositionLocal carrying the user-picked "chrome tint" override
 * (ported from tonearmboy's D.25.1 work). `null` = unset, surfaces
 * fall through to their natural tint source (the Palette-from-cover
 * extraction on `PlaybackScreen`, the static brand colours elsewhere).
 * Non-null = "the user explicitly wants this colour everywhere a
 * surface consults a chrome tint."
 *
 * Lives as a `staticCompositionLocalOf` rather than threaded through
 * every composable that reads it because the value is set once at the
 * theme root (`WhisperboyTheme`) and changes only when the user edits
 * the settings row — a static local matches that "rarely changes,
 * read in many leaf places" shape exactly.
 */
val LocalCustomChromeTint = staticCompositionLocalOf<Color?> { null }

/**
 * CompositionLocal flagging whether `PlaybackScreen`'s Palette-from-
 * cover-art tint should be applied. Default `true` — pre-existing
 * behaviour. Wired at the theme root from `ThemeSettings.tintChromeByAlbumArt`.
 * Surface-level consumers (today: `PlaybackScreen`) skip Palette
 * extraction / fall back to the static surface gradient when this is
 * `false`. `LocalCustomChromeTint` (when set) still overrides everything.
 */
val LocalTintByAlbumArt = staticCompositionLocalOf { true }

/**
 * CompositionLocal flagging whether the fullscreen blurred-cover
 * background layer is active. Activity-root WhisperboyActivity reads
 * this to decide whether to paint [AlbumArtBackground] behind the
 * theme's transparent root surface; sheet/dialog hosts that overlay
 * the library can read it to paint their own cover wallpaper layer
 * so the user doesn't see the library through translucent chrome.
 */
val LocalAlbumArtBackgroundActive = staticCompositionLocalOf { false }

/**
 * CompositionLocal carrying the file:// URI (or null) of the
 * currently-playing book's cover, for the activity / sheet bg
 * layers to render.
 */
val LocalPlayingCoverUri = staticCompositionLocalOf<String?> { null }

/**
 * Phase K.5 — reads the user's theme mode + dynamic-color preference
 * from [ThemeSettings] and applies them. The mode/flag flow into
 * `colorScheme` selection here; everything below stays unchanged from
 * the M3E A.3 wiring (MaterialExpressiveTheme + Typography).
 *
 * Two custom-colour knobs (K.5 follow-up — ported from tonearmboy
 * `82d6248`) layer on top of the mode/dynamic-color axes:
 *
 *  - `customBaseSeed`: when non-zero, becomes the Material 3 seed for
 *    both light and dark schemes regardless of mode + dynamic-color.
 *    Overrides Material You and the static fallback both — the user
 *    has explicitly said "I want a palette derived from this colour".
 *  - `customChromeTint`: when non-zero, published via
 *    [LocalCustomChromeTint] for consumers (today: `PlaybackScreen`'s
 *    background gradient) to override their cover-derived tint with.
 *
 * Initial values mirror the persisted defaults
 * ([ThemeMode.FollowSystem], dynamic-color = `true`, both custom
 * colours unset / `0L`) so first-frame rendering before DataStore
 * emits matches the steady-state default.
 */
@Composable
fun WhisperboyTheme(
  themeSettings: ThemeSettings,
  nowPlayingState: NowPlayingState,
  content: @Composable () -> Unit,
) {
  val mode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.FollowSystem)
  val dynamicColor by themeSettings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
  val customBaseSeed by themeSettings.customBaseSeed.collectAsStateWithLifecycle(initialValue = 0L)
  val customChromeTint by themeSettings.customChromeTint.collectAsStateWithLifecycle(initialValue = 0L)
  val tintByAlbumArt by themeSettings.tintChromeByAlbumArt.collectAsStateWithLifecycle(initialValue = true)
  val albumArtBackgroundEnabled by themeSettings.albumArtBackgroundEnabled.collectAsStateWithLifecycle(initialValue = true)

  val darkTheme = when (mode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.FollowSystem -> isSystemInDarkTheme()
  }

  // Override order: customBaseSeed (when set) wins outright, otherwise
  // dynamic-color on API 31+, otherwise the static fallback.
  val baseScheme = if (customBaseSeed != 0L) {
    deriveCustomScheme(customBaseSeed, darkTheme)
  } else when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  val chromeTint: Color? = if (customChromeTint == 0L) null else colorFromRgbLong(customChromeTint)

  // Lift the album-art tint out of `PlaybackScreen` and into the
  // theme so the whole app chrome (library cards, settings rows,
  // bookmark surfaces, mini-player) shifts toward the currently-
  // playing book's dominant cover color — not just the player
  // screen's vertical gradient. Mirrors tonearmboy's pattern
  // (`AlbumPalette` + `LocalAlbumPalette` + 9-slot surface blend).
  val albumPalette = rememberPlayingBookPalette(nowPlayingState)

  // Effective tint resolution: user-picked override beats Palette
  // extraction, and the "tint by album art" toggle gates Palette.
  // `null` means "no blending, surfaces stay at their base scheme".
  val effectiveTint: Color? = chromeTint
    ?: if (tintByAlbumArt) albumPalette.surfaceTint else null

  // Blend the nine surface slots tonearmboy blends (Theme.kt:50-120).
  // **Fraction 0.4 matches tonearmboy** — the prior 0.08-0.18 ladder
  // was too subtle to actually read on the user's device (custom tint
  // appeared to do nothing). 0.4 means "chrome is meaningfully tinted
  // toward the seed colour", visible at AMOLED dark with non-saturated
  // picks. Same surface ladder + same fraction tonearmboy validated.
  val tintedScheme = if (effectiveTint == null) baseScheme else baseScheme.copy(
    surface = blendSurface(baseScheme.surface, effectiveTint, 0.4f),
    surfaceVariant = blendSurface(baseScheme.surfaceVariant, effectiveTint, 0.4f),
    background = blendSurface(baseScheme.background, effectiveTint, 0.4f),
    surfaceContainerLowest = blendSurface(baseScheme.surfaceContainerLowest, effectiveTint, 0.4f),
    surfaceContainerLow = blendSurface(baseScheme.surfaceContainerLow, effectiveTint, 0.4f),
    surfaceContainer = blendSurface(baseScheme.surfaceContainer, effectiveTint, 0.4f),
    surfaceContainerHigh = blendSurface(baseScheme.surfaceContainerHigh, effectiveTint, 0.4f),
    surfaceContainerHighest = blendSurface(baseScheme.surfaceContainerHighest, effectiveTint, 0.4f),
    secondaryContainer = blendSurface(baseScheme.secondaryContainer, effectiveTint, 0.4f),
  )

  // Harmony-glass — ported from tonearmboy. When the album-art
  // background layer is painting a blurred cover fullscreen behind
  // the chrome:
  //   - `background` (Scaffold container) → fully transparent, so
  //     the scaffold's void band reveals the cover.
  //   - `surface`, `surfaceContainerHigh`, `surfaceContainerHighest`
  //     (chrome tier: TopAppBar, NowPlayingBar, dialogs, sheets) →
  //     alpha 0.5 so the cover bleeds through but the tinted chrome
  //     tier stays readable.
  //   - other surface tokens stay opaque — Cards / placeholder
  //     containers need solid backgrounds for legibility.
  val coverPath = (nowPlayingState.state.value as? com.eight87.whisperboy.playback.PlaybackUiState.Loaded)?.book?.coverPath
  val backgroundActive = tintByAlbumArt && albumArtBackgroundEnabled && !coverPath.isNullOrBlank()
  val colorScheme = if (!backgroundActive) tintedScheme else {
    val chromeAlpha = 0.5f
    tintedScheme.copy(
      background = Color.Transparent,
      surface = tintedScheme.surface.copy(alpha = chromeAlpha),
      surfaceContainerHigh = tintedScheme.surfaceContainerHigh.copy(alpha = chromeAlpha),
      surfaceContainerHighest = tintedScheme.surfaceContainerHighest.copy(alpha = chromeAlpha),
    )
  }

  val coverUri: String? = coverPath?.takeIf { it.isNotBlank() }?.let { path ->
    if (path.startsWith("file:") || path.startsWith("content:") || path.startsWith("http")) path
    else "file://$path"
  }
  CompositionLocalProvider(
    LocalCustomChromeTint provides chromeTint,
    LocalTintByAlbumArt provides tintByAlbumArt,
    LocalAlbumPalette provides albumPalette,
    LocalAlbumArtBackgroundActive provides backgroundActive,
    LocalPlayingCoverUri provides coverUri,
  ) {
    // m3-expressive A.3 — `MaterialExpressiveTheme` pulls in the new
    // motion / typography / shape defaults (rounded extra-large group
    // shapes, faster spring-based motion). It still resolves
    // `MaterialTheme.colorScheme` against whatever scheme we hand in.
    //
    // cold-start-perf B.2 — no per-color `animateColorAsState` chains
    // here. Theme is a pure function call; recomposing on dynamic-color
    // change is cheap. The animated-tint pattern is a tonearmboy thing
    // (album-art palette blending) that whisperboy has no need for —
    // audiobook chrome doesn't shift per-track.
    MaterialExpressiveTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}

/**
 * Derive a Material 3 [ColorScheme] from a 24-bit RGB seed (ported
 * from tonearmboy's D.25.1 work).
 *
 * Strategy: build primary / secondary / tertiary tonal anchors by
 * shifting the seed's hue (secondary = +30°, tertiary = +60°) and
 * lightness, then plug them into the canonical
 * `lightColorScheme` / `darkColorScheme` factories. This sidesteps
 * Material 3's `dynamicColorScheme(seed, isDark)` (added in 1.4) so
 * the build works regardless of the active Material 3 version.
 *
 * Pure helper for unit-testability — no Compose runtime required.
 */
internal fun deriveCustomScheme(seedRgb: Long, darkTheme: Boolean): ColorScheme {
  val primary = colorFromRgbLong(seedRgb)
  val (h, s, _) = rgbToHslTriple(primary)
  val secondary = hslColor(((h + 30f) % 360f), (s * 0.7f).coerceIn(0f, 1f), if (darkTheme) 0.7f else 0.45f)
  val tertiary = hslColor(((h + 60f) % 360f), (s * 0.6f).coerceIn(0f, 1f), if (darkTheme) 0.7f else 0.5f)
  val primaryDark = hslColor(h, s, if (darkTheme) 0.7f else 0.4f)
  val onPrimary = if (luminance(primaryDark) > 0.5f) Color.Black else Color.White

  // Surface-tier custom-color fix — previously this factory only overrode primary /
  // secondary / tertiary / onPrimary, leaving `surface`, `surfaceContainer*`, `background`,
  // `surfaceVariant` at their neutral [darkColorScheme] / [lightColorScheme] defaults.
  // Result: picking "Custom" tinted buttons + small accents but the dominant surfaces
  // (cards, sheets, top app bar, background) stayed neutral dark/light, which the user
  // perceived as "custom color doesn't work". M3's `dynamicDarkColorScheme` / Material You
  // tints the whole tier from the seed; we do a poor-man's blend here against the neutral
  // anchor so the seed actually carries through visually.
  //
  // Blend intensities mirror Material You's tonal ladder (subtle on `surface`, increasing
  // on the container tiers, strongest on `surfaceTint` which drives M3 elevation overlays
  // and ripples). Numbers chosen empirically against a vivid red seed on AMOLED dark:
  // tier reads "tinted dark grey" without ever drifting toward "primary-colored card".
  val neutralSurface = if (darkTheme) Color(0xFF121212) else Color(0xFFFDFCFF)
  val neutralBackground = if (darkTheme) Color(0xFF0E0E0E) else Color(0xFFFDFCFF)
  val neutralSurfaceVariant = if (darkTheme) Color(0xFF49454F) else Color(0xFFE7E0EC)

  val surface = blendSurface(neutralSurface, primaryDark, if (darkTheme) 0.06f else 0.04f)
  val background = blendSurface(neutralBackground, primaryDark, if (darkTheme) 0.05f else 0.03f)
  val surfaceContainerLow = blendSurface(neutralSurface, primaryDark, if (darkTheme) 0.08f else 0.05f)
  val surfaceContainer = blendSurface(neutralSurface, primaryDark, if (darkTheme) 0.10f else 0.07f)
  val surfaceContainerHigh = blendSurface(neutralSurface, primaryDark, if (darkTheme) 0.13f else 0.09f)
  val surfaceContainerHighest = blendSurface(neutralSurface, primaryDark, if (darkTheme) 0.16f else 0.12f)
  val surfaceVariant = blendSurface(neutralSurfaceVariant, secondary, if (darkTheme) 0.12f else 0.10f)
  val onSurface = if (darkTheme) Color(0xFFE6E1E5) else Color(0xFF1C1B1F)
  val onBackground = onSurface
  val onSurfaceVariant = if (darkTheme) Color(0xFFCAC4D0) else Color(0xFF49454F)

  return if (darkTheme) {
    darkColorScheme(
      primary = primaryDark,
      secondary = secondary,
      tertiary = tertiary,
      onPrimary = onPrimary,
      surface = surface,
      background = background,
      surfaceVariant = surfaceVariant,
      surfaceContainerLowest = blendSurface(neutralSurface, primaryDark, 0.04f),
      surfaceContainerLow = surfaceContainerLow,
      surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh,
      surfaceContainerHighest = surfaceContainerHighest,
      surfaceTint = primaryDark,
      onSurface = onSurface,
      onBackground = onBackground,
      onSurfaceVariant = onSurfaceVariant,
    )
  } else {
    lightColorScheme(
      primary = primaryDark,
      secondary = secondary,
      tertiary = tertiary,
      onPrimary = onPrimary,
      surface = surface,
      background = background,
      surfaceVariant = surfaceVariant,
      surfaceContainerLowest = blendSurface(neutralSurface, primaryDark, 0.02f),
      surfaceContainerLow = surfaceContainerLow,
      surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh,
      surfaceContainerHighest = surfaceContainerHighest,
      surfaceTint = primaryDark,
      onSurface = onSurface,
      onBackground = onBackground,
      onSurfaceVariant = onSurfaceVariant,
    )
  }
}

/**
 * Linear-RGB blend of two colors. `alpha = 0` → all `base`; `alpha = 1` → all `tint`.
 * Used by [deriveCustomScheme] to mix neutral surface anchors with the user's seed so the
 * Material 3 surface-tier ladder picks up the picked color without overwhelming it.
 */
internal fun blendSurface(base: Color, tint: Color, alpha: Float): Color {
  val a = alpha.coerceIn(0f, 1f)
  return Color(
    red = (base.red * (1f - a) + tint.red * a).coerceIn(0f, 1f),
    green = (base.green * (1f - a) + tint.green * a).coerceIn(0f, 1f),
    blue = (base.blue * (1f - a) + tint.blue * a).coerceIn(0f, 1f),
    alpha = 1f,
  )
}

internal fun colorFromRgbLong(rgb: Long): Color {
  val r = ((rgb shr 16) and 0xFFL).toInt()
  val g = ((rgb shr 8) and 0xFFL).toInt()
  val b = (rgb and 0xFFL).toInt()
  return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)
}

/** Returns (hue 0..360, saturation 0..1, lightness 0..1). */
internal fun rgbToHslTriple(c: Color): Triple<Float, Float, Float> {
  val r = c.red; val g = c.green; val b = c.blue
  val max = maxOf(r, g, b); val min = minOf(r, g, b)
  val l = (max + min) / 2f
  val delta = max - min
  if (delta == 0f) return Triple(0f, 0f, l)
  val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
  val h = when (max) {
    r -> 60f * (((g - b) / delta) % 6f)
    g -> 60f * (((b - r) / delta) + 2f)
    else -> 60f * (((r - g) / delta) + 4f)
  }.let { if (it < 0f) it + 360f else it }
  return Triple(h, s, l)
}

internal fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
  val h = ((hue % 360f) + 360f) % 360f
  val s = saturation.coerceIn(0f, 1f)
  val l = lightness.coerceIn(0f, 1f)
  val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
  val hp = h / 60f
  val x = c * (1f - kotlin.math.abs((hp % 2f) - 1f))
  val (r1, g1, b1) = when {
    hp < 1f -> Triple(c, x, 0f)
    hp < 2f -> Triple(x, c, 0f)
    hp < 3f -> Triple(0f, c, x)
    hp < 4f -> Triple(0f, x, c)
    hp < 5f -> Triple(x, 0f, c)
    else -> Triple(c, 0f, x)
  }
  val m = l - c / 2f
  return Color(red = (r1 + m).coerceIn(0f, 1f), green = (g1 + m).coerceIn(0f, 1f), blue = (b1 + m).coerceIn(0f, 1f), alpha = 1f)
}

internal fun luminance(c: Color): Float {
  // Relative luminance per WCAG-ish; cheap enough for an on-color decision.
  return 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
}
