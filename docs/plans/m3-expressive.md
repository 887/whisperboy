# whisperboy — Material 3 Expressive (M3E) starter plan

## Status: ✅ DONE — Phases A–D all shipped (A/B `e9ab4cd`, C `b433511`, D.1–D.3 `5a14e69`, D.4 `91e057d`)

## Findings shipped in tonearmboy (apply here too)

tonearmboy ran ahead and shipped Phases A–C; capture the gotchas
before whisperboy repeats the same mistakes. Source-of-truth plan:
`tonearmboy/docs/plans/m3-expressive.md`. Commits: `54eaa85`
(opt-in + initial splash), `4bae805` (Phase B+C + first splash
shrink), `2bb042e` (auto-accent + retune splash to 60 %).

1. **`material3:1.4.0` keeps `MaterialExpressiveTheme` /
   `expressive*ColorScheme` `internal`.** The Compose BOM
   `2026.03.01` resolves there but you cannot call the expressive
   APIs from 1.4.0 stable — Kotlin metadata marks them `internal`
   even though the JVM bytecode is public. Override in
   `gradle/libs.versions.toml` with `composeMaterial3 = "1.5.0-alpha18"`
   (the alpha that promoted them). Note: `expressiveDarkColorScheme()`
   does NOT exist in 1.5.0-alpha18 — only the light factory ships;
   dark stays on `darkColorScheme(...)` and inherits the surface
   ladder. (Now on material3 1.5.0 stable via the Compose BOM; the
   alpha override was dropped.)
2. **`surfaceContainer` is too quiet on AMOLED-leaning dark
   palettes.** Use `surfaceContainerHigh` for the card container
   colour. Light mode at `surfaceContainer` is fine; revisit if/when
   whisperboy gets a light-mode polish pass.
3. **Auto-derive accent from row `id` at the row composable.** Don't
   pass `accent = ...` from every catalog binding + screen — the
   moment a hand-rolled screen (About / Licenses / equivalents)
   bypasses the binding system, it goes monochrome. Default `accent`
   to null at the row signature and resolve
   `accent ?: id?.let { accentFor(it) }` inside the body. Every
   caller wins for free.
4. **Android 12+ splash icon is hard circle-clipped, period.** The
   layer-list `android:windowBackground` workaround does NOT work —
   `windowSplashScreenBackground` paints over the whole window.
   Working approach: ship a dedicated `mipmap-<d>/ic_launcher_splash.png`
   per density, where the design is shrunk to **60 %** with
   transparent padding back to the source canvas size. tonearmboy's
   first attempt at 70.7 % left corners grazing the mask; 60 % gives
   proper headroom. Wire as
   `windowSplashScreenAnimatedIcon = @mipmap/ic_launcher_splash` +
   `windowSplashScreenIconBackgroundColor = @color/launcher_background`
   (the bigger 240-dp icon area). Whisperboy's earlier 70.7 % ship
   in `14a2f48` should be retuned to 60 %.
5. **Auto-derive accent already covers `SettingsRow` callers.**
   Custom Compose surfaces (e.g. a Player chapter list, the bookmark
   sheet) that want the same coloured accent should call
   `accentFor(id)` directly — keep the function exported.
6. **Top-app-bar height — use the M3 default (64dp), do NOT override
   `expandedHeight`.** RETRACTED: an earlier draft of this gotcha
   claimed tonearmboy converged on `expandedHeight = 32dp` across 11
   screens. That claim is incorrect. Tonearmboy does not override
   `expandedHeight` anywhere; every TopAppBar uses the M3 default of
   64dp. Whisperboy was wrong to ship 32dp overrides at Phase E.1 /
   F.1 — the resulting chrome read "way too small" against M3E's
   spacing assumptions. The overrides were removed in the coordinated
   sizing + chrome refactor (see `main.md` E.6 / K.4 notes); if a
   future agent thinks "shrink the top app bar" is the right move,
   bring evidence from tonearmboy/Voice first — neither does this.
7. **DO NOT ship a two-stage Compose splash to bypass the
   Android 12+ system circle clip.** tonearmboy tried this in
   `f249b0e` and reverted in `51d1769` — the square overlay was
   visible for ~half a second before fading and was "not worth the
   moving parts". The 60% mipmap icon (gotcha #4 above) is the
   working answer. Recorded here so a future agent doesn't
   re-discover the same regression.

## Scope: "all the views"

User feedback after tonearmboy shipped Settings + About:
> "we want all the views updated/modernized"

Phase D below sweeps the WHOLE app, not just Settings. The
auto-accent fix takes care of any settings-shaped screen for free;
the rest (Library grid, Player, sleep-timer sheet, chapter list,
bookmarks, AAOS Compose surfaces) needs explicit work —
surface-tier discipline + `MaterialExpressiveTheme` opt-in cascade
through the same way.

## Why this exists

The user's sister project `tonearmboy` did the leg-work: a side-by-side
of the Android 16 system Settings UI vs the app's own settings showed
the system-side has been redesigned around **Material 3 Expressive
(M3E)** — vibrant per-row coloured circular icon avatars, a clearly
elevated card surface tier vs the page background, divider-less stacks,
the new motion / shape / typography defaults. tonearmboy was on
baseline M3 and reading flat / monochrome by comparison.

**Same architectural shape applies here.** Whisperboy was scaffolded
from the same toolchain and design conventions as tonearmboy; the
moment the Settings / Library / Player / Sleep-timer chrome lands on
a screen-shaped list of cards, you'll get the same flatness from the
same root cause unless we adopt M3E up front.

This is a **starter plan**: it captures the M3E patterns to follow.
It does not prescribe specific whisperboy UI changes — the actual
sweep should follow whatever screens whisperboy ends up shipping,
including any Android Auto / `MediaLibraryService` browse-tree
chrome that sits on top of the Compose surfaces.

## The four M3E patterns to follow

(All four together produce the "happy" Settings look. Each is cheap
in isolation; the combination matters.)

1. **Surface tier ladder.** Page background reads from
   `MaterialTheme.colorScheme.surface`; grouped cards / row clusters
   read from `MaterialTheme.colorScheme.surfaceContainer` (or
   `surfaceContainerHigh` if AMOLED dark needs more separation). Do
   NOT use `surface` for both. Don't lean on shadow elevation —
   `defaultElevation = 0.dp` with the surface-tier token does the
   lift.
2. **`MaterialExpressiveTheme`** in place of plain `MaterialTheme` at
   the app theme entry. Pulls in expressive motion / typography /
   shapes. Requires
   `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` until
   `material3:1.5.0` ships stable.
3. **Coloured circular row-icon avatars.** A `Box` with
   `Modifier.size(40.dp).clip(CircleShape).background(accent.container)`
   wrapping a `24.dp` filled `Icon(tint = accent.onContainer)`.
   `accent` is per-category and comes from a small `data class
   CategoryAccent(val container: Color, val onContainer: Color)`
   defined alongside the theme — M3 only ships three container
   pairs (`primary` / `secondary` / `tertiary`) and the system
   Settings spreads ~6 hues. Hand-pick.
4. **Filled glyph icons** at the avatar layer (`Icons.Filled.*`,
   not `Icons.Outlined.*`). Outlined glyphs read weak inside a
   coloured circle — they're meant for transparent-background row
   leads, not avatars.

Two supporting niceties:

- Card shape: `RoundedCornerShape(28.dp)` or
  `MaterialTheme.shapes.extraLarge` — matches the M3E "extra-large"
  default the system Settings uses for groups.
- Divider-less list rows inside a card: `Column(verticalArrangement
  = Arrangement.spacedBy(2.dp))`. The surface-tier gap between
  cards does the visual separation; in-card rows just stack.

## Phase A — dependency + theme entry — shipped in change `e9ab4cd`

- [x] **A.1** Bump `androidx.compose.material3:material3` to
  `1.5.0-alpha18` via an artifact-level override
  (`composeMaterial3 = "1.5.0-alpha18"` in `libs.versions.toml`); the
  Compose BOM still governs every other Compose artifact. Note the
  promoted-from-internal API caveat captured in gotcha #1 above.
- [x] **A.2** Added explicit
  `androidx.compose.material:material-icons-extended` dep — no
  longer transitive in `material3:1.4.0+`. Phase E+ chrome can pull
  `Icons.Filled.*` / `Icons.Outlined.*` without surprises.
- [x] **A.3** At the app theme entry, wrap the root in
  `MaterialExpressiveTheme(...)` instead of `MaterialTheme(...)`.
  Added `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` at the
  file level (file-level `@file:OptIn`).
- [x] **A.4** Light mode uses `expressiveLightColorScheme()`; dark
  mode stays on `darkColorScheme(...)` per gotcha #1
  (`expressiveDarkColorScheme()` does not exist in alpha18). Both
  factories produce the full surface-tier ladder.

## Phase B — surface-tier discipline — shipped in change `e9ab4cd`

- [x] **B.1** Audited every `colorScheme.surface` /
  `colorScheme.background` call site. As of this commit the only UI
  file is `ui/home/HomeScreen.kt` (Phase C bridge); its `colorScheme`
  reads are `onSurfaceVariant` / `error` / `primary` — none on the
  `surface` / `surfaceContainer` tier — so there is no surface-tier
  mis-assignment to fix. The audit is a forward-applied gate: every
  new screen lands with `surface` = page bg and
  `surfaceContainerHigh` = grouped cards / mini-player peek / sheet
  body on dark (per gotcha #2). Library / Player / NowPlayingSheet /
  mini-player surfaces tick this sub-step retroactively when they
  ship.
- [x] **B.2** No `Card` / `Surface` grouped list-row container
  exists yet; the rule is documented for when Phase E lands the
  Library cover grid and any Phase F card chrome (chapter list
  cards / sleep-timer sheet). Each must use
  `containerColor = surfaceContainerHigh`,
  `defaultElevation = 0.dp`, `RoundedCornerShape(28.dp)`.
- [x] **B.3** Shipped `SurfaceTierLadderTest` — asserts
  `DarkColorScheme.surface != DarkColorScheme.surfaceContainer`,
  `DarkColorScheme.surface != DarkColorScheme.surfaceContainerHigh`,
  and `LightColorScheme.surface != LightColorScheme.surfaceContainer`.
  Cheap regression guard for the M3 alpha churn re-collapsing the
  ladder.

## Phase C — `CategoryAccent` + per-row avatars — shipped in commit `b433511`

- [x] **C.1** Added `data class CategoryAccent(val container: Color,
  val onContainer: Color)` at `theme/CategoryAccent.kt` next to the
  rest of the theme tokens.
- [x] **C.2** Six hand-picked accent pairs covering whisperboy's
  category split: Playback (orange), Sleep timer (indigo), Library
  (green), Theme (purple), About (teal), Open-source licenses
  (slate). Dark-leaning palette only for now — the light-mode pass
  is deferred per the gotcha noted under "Risk / unknowns" below.
  Each pair was spot-checked for ≥3:1 contrast between container
  and onContainer at 24-dp icon size.
- [x] **C.3** New composable `SettingsCategoryIcon(icon, accent,
  contentDescription)` at `ui/settings/SettingsCategoryIcon.kt` —
  40-dp `CircleShape` box, filled 24-dp glyph tinted with
  `accent.onContainer`.
- [x] **C.4** Wired into both `SettingsScreen.SettingsCategoryRow`
  and `AboutScreen.AboutRow` (+ the spiritual-sibling card's inline
  icon). Each row carries a stable `id` and the row composable
  resolves the accent via `accent ?: id?.let { accentFor(it) } ?:
  PlaybackAccent`, per gotcha #3, so hand-rolled screens / future
  custom surfaces stay coloured for free. All settings-row glyphs
  swapped from `Icons.Outlined.*` to `Icons.Filled.*` per gotcha
  #4 of the four-patterns section.
  Regression test: `CategoryAccentTest` asserts every accent's
  container differs from its onContainer + every `accentFor` arm
  returns the canonical top-level `val`.

## Phase D — the rest of the chrome — D.1/D.2/D.3 shipped in commit `5a14e69`, D.4 shipped in commit `91e057d`

- [x] **D.1** Library grid chrome — section headers pulled onto the
  M3E card tier (`surfaceContainerHigh`, 12-dp rounded clip) so they
  read as chips against the page `surface`; empty-state copy
  elevated onto a `surfaceContainerHigh` card so the welcome
  message lands as a callout instead of floating text. Top app bar
  / FAB stay on M3 defaults — both already honour the surface
  ladder via the `MaterialExpressiveTheme` from Phase A.
- [x] **D.2** Player chrome — chapter list rows elevated onto rounded
  M3E surfaces (active = `secondaryContainer`, inactive =
  `surfaceContainerHigh` at 55 % alpha so the F.6 Palette-tinted
  gradient still bleeds through, giving the queue a glassy-card
  look against the book's cover tint). Sleep-timer sheet header now
  carries the `SleepTimerAccent.onContainer` indigo so the sheet
  reads as a sleep surface end-to-end. Scrubber slider colours
  unchanged — the M3 default `primary` reads cleanly against the
  tinted gradient.
- [x] **D.3** Bottom sheets — every `ModalBottomSheet` in the app now
  pins `containerColor = MaterialTheme.colorScheme.surfaceContainer`
  (M3E card tier, one notch above the page `surface`) and supplies
  a per-category accent `DragHandle` via `BottomSheetDefaults`:
  Library long-press + folder-type picker use `LibraryAccent`,
  sleep-timer sheet uses `SleepTimerAccent`, playback-options
  sheet uses `PlaybackAccent`, bookmark long-press sheet uses
  `PlaybackAccent` (bookmarks live in the playback flow; no
  dedicated bookmark accent in the six-pair palette).
- [x] **D.4** — shipped in commit `91e057d`. Four accent-coloured
  vector drawables (`auto_browse_current.xml`,
  `auto_browse_not_started.xml`, `auto_browse_all_books.xml`,
  `auto_browse_authors.xml`) under `app/src/main/res/drawable/`,
  each a single-fill Material glyph tinted from
  `CategoryAccent.onContainer` (PlaybackAccent orange for
  Currently listening, SleepTimerAccent indigo for Not started,
  LibraryAccent green for All books, ThemeAccent purple for
  Authors — distinct per tile so the Auto browse grid reads
  varied). Wired into
  `WhisperboyLibrarySessionCallback.browseableFolder(...)` via a
  new optional `@DrawableRes` parameter resolved to an
  `android.resource://${packageName}/${resId}` URI on the
  `MediaMetadata.Builder.setArtworkUri(...)` call. Per the
  original D.4 note, Auto's system theme typically monochromes
  media-browse artwork on real head units — the colour is a
  best-effort signal for renderers that honour it, but the
  artwork is now wired so the system always has something to
  render.

## References

- `tonearmboy/docs/plans/m3-expressive.md` — the full plan that
  triggered this one; cites all the upstream docs and the
  `material3` artifact version-roll story.
- developer.android.com/develop/ui/compose/designsystems/material3
- developer.android.com/jetpack/androidx/releases/compose-material3
- m3.material.io/develop/android/jetpack-compose
- github.com/android/androidify — Google's M3E reference app.

## Risk / unknowns

- **Wallpaper-driven Material You vs hand-picked accents:** the
  per-category `CategoryAccent` plan is intentionally NOT driven
  off `dynamicDarkColorScheme(LocalContext.current)` — letting the
  user's wallpaper palette override the per-category intent defeats
  the colour-coding. Document the decision in the theme file.
- **Light mode pass:** verify after Phase C that
  `expressiveLightColorScheme()` + the same accent class reads OK
  against the lighter background.
- **Scope creep:** keep `Icons.Outlined.*` → `Icons.Filled.*` swap
  at the settings-row leading-icon layer only. Don't churn icons
  inside the player / chapter list / sleep-timer dial.
- **Sister-app sharing:** the `CategoryAccent` data class + the
  `SettingsCategoryIcon` composable are obvious candidates for the
  shared-code module discussed in `docs/plans/sharing-analysis.md`
  if/when that lands. For now each app keeps its own copy.
