# whisperboy — Claude instructions

Modern Android audiobook player. Kotlin + Jetpack Compose + Media3 + Room + DataStore + SAF. Spiritual sibling to [Voice](https://github.com/PaulWoitaschek/Voice). Built entirely from the CLI, no Android Studio required, no QEMU emulator. Sister app to [tonearmboy](https://github.com/887/tonearmboy) (music player) — same toolchain and conventions, different data model and feature set; see [`docs/plans/sharing-analysis.md`](docs/plans/sharing-analysis.md) for the cross-app shared-code analysis.

## Architectural decisions (locked)

- **Language:** Kotlin only. No Java.
- **UI:** Jetpack Compose. No Android Views.
- **Audio:** [androidx.media3](https://developer.android.com/media/media3) (ExoPlayer + MediaSession + **`MediaLibraryService`**). The `MediaLibraryService` choice — vs. the simpler `MediaSessionService` tonearmboy uses — is locked: it is required for Android Auto / Automotive media-browse trees, which is a Phase N goal. Same audio engine; richer service.
- **Data, library:** Room for cached book / chapter / bookmark metadata. Per-book position lives on the `BookContent` row — single source of truth, not a separate table.
- **Storage, audio files:** **SAF only** (Storage Access Framework, `DocumentFile` + persisted URI permissions). No `READ_EXTERNAL_STORAGE`, no MediaStore. Audiobook collections live wherever the user keeps them (frequently on SD card, frequently in folders the user explicitly opts into) — SAF is the modern Android-correct path. We will wrap `DocumentFile` in a `CachedDocumentFile` to mitigate SAF's well-known performance pain.
- **Settings, preferences:** DataStore (Preferences) for sleep timer defaults, seek-back-on-resume seconds, theme, etc. Per-book settings (speed, skip silence, gain) live on the `BookContent` row.
- **Build front-end:** [Google's Android CLI](https://developer.android.com/tools/agents/android-cli) (`android` command, launched April 2026). Wraps project creation, SDK management, build, install, and run. **Do not introduce Android Studio project files** (`.idea/`, `*.iml`).
- **Build back-end:** Gradle (driven by the Android CLI; the wrapper is committed to the repo).
- **Tests, unit:** Robolectric. JVM-only. No device required.
- **Tests, UI:** [mobile-mcp](https://github.com/mobile-next/mobile-mcp), Claude-driven over ADB. **Current target: headless AVD `medium_phone`** (Android 16 / API 36, RSS ~3.2 GB), started without window/audio/snapshot. Phone via wifi-adb is the long-term home once notification + lock-screen + sleep-timer fade-out behaviour matters; Waydroid was declined (would need root).

### Why SAF (not MediaStore)

Tonearmboy uses MediaStore because music collections are typically in `/sdcard/Music/`, indexed by the system, and the affordances MediaStore provides (alphabetical-by-artist views, full-library scan, change observer) line up with how people consume music. Audiobooks are different:

- Often live in `/sdcard/Audiobooks/` or `/sdcard/Documents/Audiobooks/` or on **SD card / OTG drives** that MediaStore does not always index reliably.
- Are *opt-in collections* — the user wants to point whisperboy at a specific folder, not see "every audio file on the device". MediaStore's all-or-nothing visibility is the wrong shape.
- Frequently consist of long M4B files (multi-hour, with embedded chapter markers) that MediaStore reports as a single track without chapter awareness — we have to read the file ourselves anyway.

So: SAF picker, persisted URI permissions, per-root folder-mode classification (`SingleFile` / `SingleFolder` / `Root` / `Author` — Voice's four), `CachedDocumentFile` wrapper for performance.

### Why `MediaLibraryService` (not `MediaSessionService`)

`MediaLibraryService extends MediaSessionService`. It adds the `MediaLibrarySession` browse tree that Android Auto / Wear OS / system media-browse clients walk. Tonearmboy doesn't need this (Phase N for tonearmboy is "delete files from the player", not "ship to Auto"). Whisperboy does — Auto support is a Phase N goal — and adding it later means rewriting the service plumbing once it has callers, which is exactly the kind of scope-creep we avoid by deciding now. Same `ExoPlayer` underneath; the difference is the service surface.

## Required CLIs and MCP servers

These are user-machine prerequisites. The plan tracks each in Phase 0.

### Android CLI

The new (April 2026) `android` command from Google wraps everything we need.

Install (userspace, this user's setup):

```bash
curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/android -o ~/.local/bin/android
chmod +x ~/.local/bin/android
android --version  # self-bootstraps the runtime on first call
```

The CLI bundles its own JDK 21 at `~/.android/cli/bundles/<hash>/jre/`. **Caveat:** the bundled JRE is *minimized* — it's missing modules including `java.rmi`, which Gradle 9.1's Kotlin DSL classpath fingerprinter loads. Direct `./gradlew` invocations against the bundled JRE will fail at configuration time with `java.lang.NoClassDefFoundError: java/rmi/Remote`. For direct Gradle calls, export `JAVA_HOME` to a full system JDK 17+ instead — for example `/usr/lib/jvm/java-27-openjdk` on this user's machine. Going through `android run` / other `android` subcommands is fine and uses the bundled toolchain internally.

Practical rule of thumb:
- `android run --apks=…` → just works.
- `./gradlew assembleDebug` → `JAVA_HOME=/usr/lib/jvm/java-27-openjdk ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug` (or equivalent system JDK 17+ path).

**Worktree caveat:** Gradle reads the SDK path from `local.properties`, which is gitignored. Worktrees created off `main` for subagents start without a `local.properties`, so direct `./gradlew` calls there will fail with `SDK location not found` unless `ANDROID_HOME` is exported (or `local.properties` is generated locally in the worktree). Always export `ANDROID_HOME=$HOME/Android/Sdk` alongside `JAVA_HOME` when invoking Gradle directly in an agent worktree.

Useful subcommands:

```bash
android create list                                  # browse project templates
android create --name=whisperboy --output=. <template>  # scaffold a new project
android sdk install platforms/android-34 build-tools/34.0.0
android run --apks=app/build/outputs/apk/debug/whisperboy-debug.apk
android docs search <query>                          # query the Android Knowledge Base
android docs fetch <kb-url>                          # fetch a specific KB doc
android skills list --long                           # browse official Android skills
android info                                         # show detected SDK + version
```

`android docs search` is **the first place to look** when uncertain about Android APIs. It returns up-to-date guidance from the official Android Knowledge Base — beats grepping web search results, and is on-machine.

### `mobile` MCP server (UI driving)

Registered at **project scope** in `.mcp.json` (committed to the repo) and allowed in `.claude/settings.json` (also committed). When a Claude Code session starts in this repo with `enableAllProjectMcpServers: true` (set in the project settings), the `mcp__mobile__*` tools become available automatically.

To re-register on a fresh checkout if for any reason the project config drops the entry:

```bash
claude mcp add mobile --scope project -- npx -y @mobilenext/mobile-mcp@latest
```

What it gives you: list connected ADB targets, install APKs, launch the app, read the accessibility tree (the screen state, the way Playwright reads the DOM), tap by label / coordinates, assert UI state.

### `android-skills` MCP server (official Android skills)

Registered at **project scope** in `.mcp.json` and allowed in `.claude/settings.json`. Surfaces Google's official Android Skills (Compose migration, Navigation 3, Edge-to-Edge, AGP 9, R8 config, Media3 patterns, etc.) as MCP tools inside Claude Code. **Consult these before hand-rolling any Android-specific pattern** that could be load-bearing on platform conventions.

To re-register on a fresh checkout:

```bash
claude mcp add android-skills --scope project -- npx -y android-skills-mcp
```

### Test target

One of:

- **wifi-adb to the user's phone** (preferred long-term — zero machine RAM cost, real lock-screen + notification behaviour, real SAF picker behaviour against actual storage):
  ```bash
  adb pair <ip>:<pair-port>      # pair once
  adb connect <ip>:<connect-port>
  adb devices                    # confirm
  ```
- **Headless AVD `medium_phone`** (Android 16 / API 36 — see Phase 0):
  ```bash
  ~/Android/Sdk/emulator/emulator -avd medium_phone \
    -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
  ```
- **Waydroid** declined (LXC, ~1-2 GB resident, but needs root for SAF tree access).

For whisperboy specifically, the AVD is fine for the cover-grid / SAF-picker / scanner / playback layer, but **sleep timer fade-out behaviour and shake-to-resume detection should ultimately be verified on a real phone** — accelerometer events from the AVD's emulated sensor pipeline are reliable but lag, and audio fade-out on the AVD goes through swiftshader's audio path which is not representative.

## Test loop

```bash
./gradlew assembleDebug
android run --apks=app/build/outputs/apk/debug/whisperboy-debug.apk
# mobile-mcp tools take over for UI interaction
```

### UI changes are verified on the running AVD

Any change that touches Compose UI (layout, composable structure, navigation, theming, anything visible) MUST be verified by installing the rebuilt debug APK on the running headless AVD (`emulator-5554`) and inspecting the result — Robolectric unit tests do not catch real-device layout bugs (overflow, clipping, off-screen widgets, scrim under the now-playing FAB, etc.).

Canonical loop:

```bash
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/whisperboy-debug.apk
adb -s emulator-5554 shell am start -n com.eight87.whisperboy/.WhisperboyActivity
adb -s emulator-5554 exec-out screencap -p | magick - -resize 50% /tmp/whisperboy.png   # then Read the PNG
```

The AVD is 1080x2400 native, which is too big to read comfortably — pipe screencaps through `magick - -resize 50%` to land at 540x1200 (quarter the pixels, easier to inspect, tap coords are still computed against the device's native 1080x2400, just multiply scaled image coords by 2). Skip the resize only when you genuinely need pixel-accurate detail.

Also: clean up `/tmp/*.png` periodically — these accumulate fast across sessions and a few hundred stale screenshots makes file listings noisy.

Prefer `mobile-mcp` tools when they're loaded in the session (they give the accessibility tree + tap-by-label, much more precise than coordinate input). When mobile-mcp isn't available, fall back to `adb exec-out screencap -p` + visual inspection of the PNG via the Read tool — it's lower-resolution evidence than the a11y tree but enough to confirm widget presence, position, and overflow behaviour.

Do not report a UI task as done on the strength of unit tests + a successful build alone.

For raw ADB inspection during dev:

```bash
adb logcat -s whisperboy:* AudioFocus:* MediaSession:* MediaBrowserService:*
adb shell am start -n com.eight87.whisperboy/.WhisperboyActivity
```

### SAF-specific test-loop notes

The SAF picker presents a system UI surface that the AVD reproduces faithfully but slowly. Two practical tips:

1. The folder a Phase D smoke test wants is `/sdcard/Audiobooks/whisperboy-test/`. Push fixtures via `scripts/push-test-audiobooks.sh` (created in Phase D), then walk the picker UI via mobile-mcp.
2. Persisted URI permissions survive app reinstall *only if* the `--user 0` is preserved on `adb install -r`. If a smoke test reports "tree URI permission lost", check that.

## File conventions

- Single-module to start. Split into `:core` / `:data` / `:ui` only when the single-module size warrants it; do not premature-modularize. (Voice is multi-module — `:core:*` × N, `:features:*` × N — and the user has a strong prior, validated on tonearmboy, that single-module is the right starting shape and modularization should follow pain, not predict it.)
- Package root: `com.eight87.whisperboy`.
- Composable functions: PascalCase, no `@Composable` on private helpers unless they take a Modifier.
- ViewModels: one per screen, talk to the data layer via repository interfaces.
- No DI framework in v1 (Hilt/Koin/Metro) — pass dependencies as constructor params via a hand-rolled `AppGraph` composition root, the same pattern tonearmboy uses. Add DI later if/when the manual wiring hurts. (Voice migrated to Metro; we don't follow them on that until whisperboy's wiring complexity actually warrants it.)
- No reflection-based JSON. Use `kotlinx.serialization` if any serialization is needed.

## Design principles — SOLID, applied to Kotlin + Compose

The codebase follows SOLID where it earns its keep. Kotlin + Compose change *how* the principles cash out (top-level functions instead of `interface ServiceImpl`, sealed types instead of Visitor, `Flow<T>` instead of Observer wiring), but the underlying tests still apply. **When introducing a new file or refactoring an existing one, sanity-check it against these five questions.** When in doubt, prefer the principle over the shortcut.

- **S — Single Responsibility.** A type / file / composable should have *one reason to change*. If you can describe what a class does without "and", "also", or "plus", you're probably fine. If a single file is editable for three independent reasons (e.g. *folder scanning* + *chapter parsing* + *Room write*), split it. Soft heuristic: anything past ~500 LOC of non-trivial Kotlin deserves a second look; past ~800 LOC almost always needs splitting.
- **O — Open/Closed.** Prefer adding a new sealed-class case / new strategy implementation over modifying an existing `when`/`if` chain that already covers the abstraction. Sealed types + exhaustive `when` are the Kotlin-native way to express "open for extension". *Caveat:* don't pre-build extension points for cases that don't exist yet — closed-by-default, opened only when a second variant arrives. Concretely for whisperboy: the four `FolderType` variants (`SingleFile` / `SingleFolder` / `Root` / `Author`) are a sealed type, scanner dispatches via exhaustive `when`. New folder modes land as new variants, not as `if (folder.kind == "...") { ... }` chains.
- **L — Liskov Substitution.** Subtypes (or sealed-type variants) must honour the contract of the parent. Every variant must satisfy the signature totally.
- **I — Interface Segregation.** Don't pass a fat type when a narrow one would do. If a screen needs only `observeBooks()` and `bookById()`, take a `BookSource` (two methods) — not the whole `LibraryRepository`. In Compose this often manifests as: don't pass a god-state object down five levels; pass the three fields the leaf actually reads.
- **D — Dependency Inversion.** High-level modules (UI, playback orchestration) depend on abstractions, not concrete classes. Concretely: ViewModels / composables take repository *interfaces* or function-typed parameters; concrete Room DAOs / SAF wrappers live behind those interfaces. The `AppGraph` is the composition root — it's the *only* place that knows the concrete types.

These are evaluation criteria, not religion — small ad-hoc helpers don't need their own interface, and one-off composables don't need to be split for principle's sake. But anything load-bearing (`LibraryRepository`, `PlaybackController`, `Scanner`, `SettingsRepository`, the chapter parsers) should pass all five.

The forward-applied refactor discipline drawn from tonearmboy's shipped SOLID work lives at [`docs/plans/refactor-solid.md`](docs/plans/refactor-solid.md). Phase C–L commits tick the relevant patterns as the code that earns them lands.

## Plan files

- [`docs/plans/main.md`](docs/plans/main.md) — phased build plan, per the user's global CLAUDE.md rule (numbered phases, sub-step checkboxes).
- [`docs/plans/sharing-analysis.md`](docs/plans/sharing-analysis.md) — cost/benefit memo on extracting shared atomic libraries between tonearmboy and whisperboy. Decision: **don't share yet.** Revisit after both apps ship 1.0 if a load-bearing hotspot emerges.
- [`docs/plans/translations.md`](docs/plans/translations.md) — i18n + l10n plan. Discipline-from-day-one (no retro extraction), user-+-Claude per-locale workflow, no third-party service, README progress table piggybacks on `--gh-release`. T.A + T.B foundational pieces shipped; T.C / T.D / T.E open as project ships UI.
- [`docs/plans/refactor-solid.md`](docs/plans/refactor-solid.md) — forward-applied SOLID discipline drawn from tonearmboy's shipped R.A–R.F refactors. Whisperboy has no god-objects yet; the plan codifies the patterns as gates on every Phase C–L commit so it stays that way. R.F.1 (delete template ballast) is the only sub-step that ticks at a known phase (E); the rest tick as the relevant phase code lands.
- [`docs/plans/oss-licenses.md`](docs/plans/oss-licenses.md) — open-source-licenses sub-page plan. Apache 2.0 §4 NOTICE compliance via `app.cash.licensee` (build-time inventory, zero runtime deps), Compose `LicensesScreen` rendering `assets/licenses/artifacts.json`, allowlist `Apache-2.0` / `MIT` / `BSD-2-Clause` / `BSD-3-Clause`. Mirrors the same shape tonearmboy + shutterboy use; extends `main.md` K.6 (About section). Phases A (plugin + JSON) → B (LicensesScreen UI) → C (tests + audit discipline).
- [`docs/plans/cold-start-perf.md`](docs/plans/cold-start-perf.md) — standing-rule plan for keeping cold start fast as features land. Forward-applied guards drawn from tonearmboy's perf-clawback session (no splash-hold, no work in `onCreate`, `collectAsStateWithLifecycle`, lambda-form `Modifier.offset`/`graphicsLayer` for animated state, stable `key = { ... }` on every `items(...)`). Read before any Phase E–F UI work or before letting `WhisperboyActivity` grow past 50 LOC. Phase F (Baseline Profile) is the only delta-of-work piece; everything else is preventive.
- [`docs/plans/cover-art.md`](docs/plans/cover-art.md) — cover-art roadmap, mirrors **Voice's `features/cover/` module** almost exactly (the user reports Voice's cover-art has "never disappointed" — high-signal subsystem to copy the shape of). Doctrine: local-first scan automatic (existing file → SAF directory first `image/*` → embedded from first 5 chapters), no automated network anything, user-initiated per-book "Search online" via **DuckDuckGo image search** (two-step `vqd` token dance, paged staggered grid of thumbnails, crop overlay after pick), one service hardcoded, no settings surface. Phases A (local-first scan) → B (user-initiated DDG search) → C (crop step) → D (refresh + invalidation). Lands first consumer at main.md Phase E (library grid + long-press "Search online" entry point); MP4 cover extractor reuses Phase I's box parser. **No K.7 — cover-art has no settings.**
- [`docs/plans/m3-expressive.md`](docs/plans/m3-expressive.md) — Material 3 Expressive (M3E) starter plan, drawn from tonearmboy's shipped Phases A–C. Captures five gotchas before whisperboy repeats them: `material3:1.4.0` kept `MaterialExpressiveTheme` `internal` (now resolved: 1.5.0 stable via the Compose BOM); `surfaceContainerHigh` beats `surfaceContainer` on AMOLED dark; auto-derive `accent` from row `id` so hand-rolled screens don't go monochrome; Android 12+ splash icon is hard circle-clipped at 60% (the `windowBackground` workaround does NOT work); `accentFor(id)` should stay exported for non-settings surfaces. Four M3E patterns (surface tier ladder, `MaterialExpressiveTheme`, coloured circular row-icon avatars, filled glyph icons). Phases A (deps + theme entry) → B (surface-tier audit) → C (`CategoryAccent` + per-row avatars) → D (sweep the rest of the chrome — library grid / player / sheets / Auto). Lands at Phase E as foundational, blooms at Phase K (settings catalog) and again at Phase N (Auto branding).

When working on a phase:

- Tick its sub-steps (`- [x]`) in the same commit that lands the work.
- Add `shipped in commit <id>` to the phase header when *all* its sub-steps are ticked.
- Mark the whole plan `## Status: ✅ DONE` once every phase is ticked.
- If a phase header has no sub-step checkboxes, *write them first*. No vibes-based progress.

## Editorial — user-facing copy

The user follows Paul Graham's *Keep Your Identity Small*. App copy (settings descriptions, error messages, About text, onboarding) should be plain, factual, useful. No "vibes" copy, no personal opinions, no humor that pins identity. This applies double to onboarding (Phase L) — Voice's onboarding is exemplary and the bar to clear.

## i18n discipline + per-locale workflow

See [`docs/plans/translations.md`](docs/plans/translations.md) for the full plan. Two rules carry directly into every Phase E–L PR:

1. **Every user-facing string lands in `app/src/main/res/values/strings.xml` in the SAME COMMIT that introduces the surface.** No `Text("...")` with literal copy in `ui/**`. testTags / log tags / debug-only strings / format-string constants / internal sentinels stay literal — those are not user-facing. Run `./scripts/check-i18n.sh` to audit `ui/**` for violations; it greps Compose source (Android lint's `HardcodedText` covers XML only).
2. **Naming scheme: `<surface>_<role>` lowercase snake** (e.g. `player_play_pause_cd`, `sleep_fade_out_label`, `folder_type_root_description`). Surfaces are documented as a leading XML comment in `values/strings.xml`.

**Per-locale session workflow** (when the user wants a new language): the user picks the target locale; Claude reads `values/strings.xml` plus the editorial brief above; Claude drafts `values-<locale>/strings.xml` with every translatable key in the same order as the canonical file; the user reviews per-entry; signed-off entries are committed; missing keys fall back to English at runtime (Android default). No third-party translation service. Adding a locale is one file; removing it is `git rm -r values-<locale>/`.

## Release workflow

The user's intended pattern: **vibing from their phone with the Claude app**,
they tell Claude "ship a new build of whisperboy." Claude opens a session against
this repo on the dev machine and runs the local build. The user then pulls the
APK via [Obtainium](https://github.com/ImranR98/Obtainium) on their phone,
which auto-detects the new GitHub Release.

**Local build is the primary path. Zero CI minutes by default.**

Canonical commands:

```bash
# Full one-shot: build + push to GH Releases + install on connected device
scripts/build-release-apk.sh --gh-release --install

# Just publish to GH Releases (Obtainium pulls from there)
scripts/build-release-apk.sh --gh-release

# Local APK only, no upload, no install
scripts/build-release-apk.sh
```

What `--gh-release` does:

1. Builds `release/whisperboy-<version>-<sha7>.apk` (debug-signed by default).
2. Generates release notes from `git log <prev-tag>..HEAD` plus a
   "Verify build" table containing the commit hash and APK SHA-256.
3. Creates the GitHub Release `v<version>-<sha7>` with the APK attached.
4. Pushes the local annotated tag to `origin`.

There is no CI release workflow: releases are only ever built and published
locally by `scripts/build-release-apk.sh`. Do not add GitHub Actions.

When a phase asks for a release, the happy path is `--gh-release --install`
against the connected AVD / wifi-adb phone.

## Subagent dispatching

Subagents working on this repo run in worktrees. Each agent prompt must:

- name the phase + sub-steps it owns
- be told to tick checkboxes and add the commit ID to the phase header as it lands work
- be told to keep the work scoped to its phase (no opportunistic refactors of unrelated code)
- be told to never modify `~/.claude/` files (those are not under this repo)
- be told to consult `android docs search <query>` before hitting general web search for Android API questions
- be told to consult the `android-skills` MCP for any pattern Google has codified (Compose migration, Navigation 3, edge-to-edge, MediaLibraryService browse trees, etc.)
- be told that **Voice is the spiritual sibling** — when in doubt about UX shape (sleep timer dialog, folder picker layout, library grid), check Voice's `:features:` modules for prior art before inventing. Whisperboy is not a fork; we don't copy code; but Voice's design space is the design space we start in.
