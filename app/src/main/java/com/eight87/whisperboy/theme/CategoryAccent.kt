package com.eight87.whisperboy.theme

import androidx.compose.ui.graphics.Color

/**
 * m3-expressive Phase C — pair of `(container, onContainer)` colours
 * for the per-row coloured circle row-icon avatars. Mirrors Material
 * 3's `primaryContainer` / `onPrimaryContainer` token shape so the
 * runtime use site reads identically to a built-in colour role.
 *
 * Hand-picked palette covering whisperboy's category split (Playback,
 * Sleep timer, Library, Theme, About, Open-source licenses). The
 * mapping from row id to accent is intentionally explicit — it makes
 * "the orange one is Playback" stable across sessions / app reinstalls
 * and lets a future hand-rolled surface (chapter list, bookmarks
 * sheet) reach for a known category accent without re-rolling the
 * hash.
 *
 * The accents are NOT driven off `dynamicDarkColorScheme()` —
 * letting the user's wallpaper steamroll the per-category intent
 * defeats the colour-coding entirely. Hand-picked stays hand-picked.
 *
 * Container tones land around lightness 22-28 % so the circle reads
 * as a quietly-saturated tile against the page; on-container tones
 * land at lightness 75-85 % for high-contrast filled glyphs. The
 * pairs were spot-checked for WCAG AA contrast at the 24-dp icon
 * size (~3:1 minimum for non-text glyphs); every pair clears.
 */
data class CategoryAccent(
  val container: Color,
  val onContainer: Color,
)

// Six hand-picked accent pairs covering whisperboy's category split.
// Each is exposed top-level so non-settings surfaces (e.g. a future
// chapter list, bookmark sheet) can reach for a known accent
// directly without going through the id-keyed `accentFor`.

/** Playback — orange. Warm "in motion" tone, matches the system Display category. */
val PlaybackAccent: CategoryAccent = CategoryAccent(
  container = Color(0xFF5C3300),
  onContainer = Color(0xFFFFB877),
)

/** Sleep timer — indigo. Cool, sleep-adjacent. */
val SleepTimerAccent: CategoryAccent = CategoryAccent(
  container = Color(0xFF1F2A6E),
  onContainer = Color(0xFFB8C4FF),
)

/** Library — green. "Collection / shelves" connotation. */
val LibraryAccent: CategoryAccent = CategoryAccent(
  container = Color(0xFF1F4D2E),
  onContainer = Color(0xFF9CDDB4),
)

/** Theme — purple. Matches the system Apps / Theme category vibe. */
val ThemeAccent: CategoryAccent = CategoryAccent(
  container = Color(0xFF3F2C73),
  onContainer = Color(0xFFD0BCFF),
)

/** About — teal. Calm, informational. */
val AboutAccent: CategoryAccent = CategoryAccent(
  container = Color(0xFF003D40),
  onContainer = Color(0xFF7FE0E6),
)

/** Open-source licenses — slate. Neutral, document-like. */
val LicensesAccent: CategoryAccent = CategoryAccent(
  container = Color(0xFF2E3A47),
  onContainer = Color(0xFFB8C7D6),
)

/**
 * Map every category accent by its row id. Public + top-level so
 * non-settings surfaces (and any test that wants to enumerate the
 * palette) can read it. Keys mirror the [accentFor] `when` cases.
 */
val darkAccents: Map<String, CategoryAccent> = mapOf(
  "playback" to PlaybackAccent,
  "sleep" to SleepTimerAccent,
  "library" to LibraryAccent,
  "theme" to ThemeAccent,
  "about" to AboutAccent,
  "licenses" to LicensesAccent,
)

/**
 * Full palette for hash-based id → accent assignment. Used by
 * [accentFor] when no named category matches the id; the hash is
 * stable across runs so a given row keeps its colour without a
 * hand-mapped table. Mirrors tonearmboy's pattern — every About /
 * Licenses sub-row gets a distinct hue instead of all collapsing
 * to the same fallback.
 */
private val AccentPalette: List<CategoryAccent> = listOf(
  PlaybackAccent,
  SleepTimerAccent,
  LibraryAccent,
  ThemeAccent,
  AboutAccent,
  LicensesAccent,
)

/**
 * Pick an accent for the row identified by [id]. Top-level / not
 * `@Composable` so non-Compose callers (and tests) can use it.
 *
 * Named category ids (`playback`, `sleep`, …) get their canonical
 * accent. Everything else hashes into the palette so each sub-row
 * (`about.app_name`, `about.github`, `about.license`, …) gets its
 * own colour deterministically — the user noticed all About sub-row
 * icons coming back the same hue because the previous `else` branch
 * fell through to [PlaybackAccent]. Tonearmboy's pattern: hash
 * `id.hashCode() % palette.size` with the negative-hash guard.
 */
fun accentFor(id: String): CategoryAccent = when (id) {
  "playback" -> PlaybackAccent
  "sleep" -> SleepTimerAccent
  "library" -> LibraryAccent
  "theme" -> ThemeAccent
  "about" -> AboutAccent
  "licenses" -> LicensesAccent
  else -> {
    val n = AccentPalette.size
    val idx = ((id.hashCode() % n) + n) % n
    AccentPalette[idx]
  }
}
