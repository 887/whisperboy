# whisperboy

Modern Android audiobook player. Built on Jetpack Compose + Media3. Spiritual sibling to [Voice](https://github.com/PaulWoitaschek/Voice) — same feature surface (SAF folder libraries, embedded chapter parsing, sleep timer with fade-out and shake-to-resume, per-book speed and skip-silence, bookmarks, Android Auto via `MediaLibraryService`), rebuilt fresh in Kotlin + Compose with the same CLI-only build philosophy as [tonearmboy](https://github.com/887/tonearmboy).

## Status

Pre-Phase 0. Repo skeleton + plans only. See [`docs/plans/main.md`](docs/plans/main.md) for the phased build plan and [`docs/plans/sharing-analysis.md`](docs/plans/sharing-analysis.md) for the tonearmboy/whisperboy shared-code analysis.

## Goals

- All the basics for an audiobook player: SAF folder library (multiple roots, four folder modes — SingleFile / SingleFolder / Root / Author), per-book position, embedded chapter parsing (M4B + Matroska), variable speed with persistence per book, skip silence, volume gain, sleep timer (timed / end-of-chapter / fade-out / shake-to-resume), bookmarks, Android Auto, headset / Bluetooth controls.
- **Cover-art-forward library UI**, Material 3 with dynamic color, progress overlay on every cover.
- Modern stack: Kotlin + Compose + Media3 + Room. No legacy Android Views, no Java.
- **Built entirely from the CLI**, no Android Studio required.

## Non-goals (v1)

- Cloud sync / Audiobookshelf / Plex / Jellyfin server integration. (Tracked as a stretch differentiator in `docs/plans/main.md`.)
- Podcast / RSS support. Whisperboy plays *audiobooks* — there is a separate [tonearmboy](https://github.com/887/tonearmboy) for music; podcasts can pick a third app.
- Cast / Wear OS / tablet-specific layouts (works on phone, scales later).
- Online cover-art search. Local + embedded only in v1.

## Why "whisperboy"?

Sister naming convention with the gallery app **shutterboy** and the music app **tonearmboy**: *<object-from-the-medium> + boy*. Shutter for cameras; tonearm for music; whisper for audiobooks. An audiobook *is* a voice whispered into your ear at close range — that's the whole register: intimate, bedside, late-night. The "boy" suffix marks these apps as a family.

## Why a sibling app, not a music+audiobook combo?

Because the data model differs and forcing both into one app produces compromises in both. Music: many short tracks per album, queue-based, MediaStore-scanned, ReplayGain. Audiobooks: few long files per book, position-per-book, SAF-scanned, sleep timer, embedded chapter markers. Tonearmboy and whisperboy share Media3 + Compose + Room as a *stack*, not as a codebase. See [`docs/plans/sharing-analysis.md`](docs/plans/sharing-analysis.md) for the cost/benefit analysis of extracting a shared library between the two — the short answer is that the shareable wins are small (a few hundred LOC each) and the cost of a subrepo / separate versioning / change-coordination overhead currently outweighs them. Revisit after both apps ship 1.0.

## Install on Android via Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) is an open-source Android
app store that pulls APKs directly from GitHub Releases. No Play Store, no
sideload dance, auto-update on every new release. Works on de-Googled Androids
(GrapheneOS / CalyxOS / LineageOS).

> **Note:** whisperboy has not shipped a release yet. The instructions below
> describe the intended install path once Phase O lands.

### One-tap install (if Obtainium is already on your phone)

Tap this link on your phone:

[`obtainium://add/https%3A%2F%2Fgithub.com%2F887%2Fwhisperboy`](obtainium://add/https%3A%2F%2Fgithub.com%2F887%2Fwhisperboy)

Obtainium opens, prefills the source, and shows **Add**.

### Manual install

1. Install Obtainium from [F-Droid](https://f-droid.org/en/packages/dev.imranr.obtainium.fdroid/) or [its GitHub releases](https://github.com/ImranR98/Obtainium/releases/latest).

2. In Obtainium, tap **Add App** → paste this **Source URL**:

   ```
   https://github.com/887/whisperboy
   ```

3. The other fields auto-detect, but if you need to set them by hand:

   | Field            | Value                  |
   | ---------------- | ---------------------- |
   | Source type      | GitHub                 |
   | APK filter regex | `^whisperboy-.*\.apk$`    |
   | Update channel   | Releases               |

4. Tap **Add**. Obtainium fetches `whisperboy-<version>-<sha7>.apk` from the latest release and offers Install. Future releases trigger an auto-update notification.

### Verifying a build

Each release ships a "Verify build" table in its notes with the APK SHA-256.
After installing, confirm what you got matches:

```bash
adb shell pm path com.eight87.whisperboy           # find the installed APK on your device
adb pull <path-from-above> /tmp/installed.apk   # pull it back
sha256sum /tmp/installed.apk                    # compare to the release notes
```

---

The rest of this README is for **developers** — building locally, running the
AVD, running smoke tests, shipping a release. Skip if you just want the app.

## Prerequisites (one-time, Linux)

```bash
# Android CLI 0.7+
curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/android \
  -o ~/.local/bin/android && chmod +x ~/.local/bin/android
android --version          # self-bootstraps the runtime + bundled JDK 21

# SDK packages
android sdk install platforms/android-34 build-tools/34.0.0

# Test target — headless AVD
android emulator create --profile=medium_phone

# Mirror utility (optional but recommended for visual QA)
sudo pacman -S scrcpy      # Arch / Manjaro
# (or your distro's equivalent — Debian/Ubuntu: `sudo apt install scrcpy`)
```

The Android CLI also fetches the emulator binary and a system image on first
`android emulator create`. Expect ~600 MB of downloads on a fresh box.

## Run the AVD + scrcpy

```bash
scripts/start-avd.sh             # boot AVD if not running, then attach scrcpy
scripts/start-avd.sh --no-mirror # AVD only, no scrcpy window
scripts/start-avd.sh --kill      # stop both
```

The AVD boots headless (`-no-window -no-audio -no-snapshot`) for ~3 GB resident
RAM. `scrcpy` then mirrors the display to a host window (Wayland / X11) without
restarting the emulator. Once running, `adb devices` shows `emulator-5554`.

## Build + install

```bash
# Direct gradlew calls need both env vars (see CLAUDE.md for the why):
export JAVA_HOME=/usr/lib/jvm/java-26-openjdk
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew assembleDebug
android run --apks=app/build/outputs/apk/debug/whisperboy-debug.apk --device=emulator-5554
```

Or via the Android CLI directly (which handles the toolchain internally):

```bash
android run --apks=app/build/outputs/apk/debug/whisperboy-debug.apk
```

## Build a release APK

The canonical happy path is **"phone-vibing"**: you're on your phone, you tell
Claude (in the Claude app) to ship a new build of whisperboy. Claude opens a
session against this repo on your dev machine, runs:

```bash
scripts/build-release-apk.sh --gh-release
```

…and the new APK shows up on `https://github.com/887/whisperboy/releases/latest`.
You then pull it to your phone via [Obtainium](#install-on-android-via-obtainium), which
auto-detects the new release and offers an in-place update. No Play Store, no
Android Studio, no manual `adb`.

The script supports three flags, individually or combined:

```bash
# 1. Build only — APK lands at release/whisperboy-<version>-<sha7>.apk
scripts/build-release-apk.sh

# 2. Build + upload to GitHub Releases (uses gh CLI; creates a vN.N.N-<sha7> tag)
scripts/build-release-apk.sh --gh-release

# 3. Build + adb install onto the connected device (AVD or wifi-adb phone)
scripts/build-release-apk.sh --install

# Combine flags — the full local one-shot:
scripts/build-release-apk.sh --gh-release --install
```

`--gh-release` does the full production handshake:

- Builds the APK and SHA-256-checksums it.
- Auto-generates release notes from `git log <prev-tag>..HEAD`, including a
  "Verify build" section listing the commit + APK SHA-256.
- Pushes the local `v<version>-<sha7>` tag to `origin` (informational; the
  fallback Action is self-disabling).

By default the APK is signed with Gradle's debug keystore (good enough for
personal sideload). For production-signed releases set the
`WHISPERBOY_RELEASE_KEYSTORE`, `WHISPERBOY_RELEASE_KEY_ALIAS`, and
`WHISPERBOY_RELEASE_KEY_PASSWORD` environment variables before running, and the
script switches to `assembleRelease`.

The `release/` directory is gitignored. Each build also writes a
`release/latest.apk` symlink for convenience.

## GitHub Actions fallback

`.github/workflows/release.yml` is a **fallback** for when the local build
isn't available — for example, if you're shipping from a phone via the GitHub
web UI. It triggers **only** on `push: tags: [v*]`; it never runs on regular
pushes, PRs, or schedule, so the default cost is zero CI minutes.

The workflow is **self-disabling**: at the start of the job it queries the
matching release; if any asset already matches `whisperboy-*.apk` (which is what
`scripts/build-release-apk.sh --gh-release` uploaded), it exits 0 without
rebuilding. So for the normal local-build flow, even though the tag push
triggers the workflow, no work happens.

To skip CI for a specific tag entirely (e.g. WIP tags), include `[skip ci]` in
the **annotated tag's message** (lightweight tags don't have a message):

```bash
git tag -a v1.0-abcdef1 -m "WIP build [skip ci]"
git push origin v1.0-abcdef1
```

If you've configured a release-signing keystore, set repo secrets
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and the
fallback build will use `assembleRelease` instead of `assembleDebug`.

## Populate the library + smoke-test

```bash
scripts/library-smoke-test.sh    # synthetic single-file + multi-file books, exercises Phase D scan path
scripts/playback-smoke-test.sh   # exercises Phase F (notification, lock-screen, headset, foreground, process death, position persistence across cold-start)
scripts/ui-smoke-test.sh         # exercises Phase E (library tabs, folder picker, settings sub-pages, sleep timer sheet)
```

## Real test audiobooks (CC-BY / public domain)

For visual QA — browsing the cover grid, scrubbing within long files, watching
chapter markers populate — synthetic sine waves aren't enough. The
test-audiobooks scripts pull a small set of public-domain LibriVox recordings
(Project Gutenberg–era texts), tag them appropriately, and lay out two book
shapes:

```bash
scripts/fetch-test-audiobooks.sh           # downloads + tags into test-audiobooks/ (gitignored)
scripts/push-test-audiobooks.sh            # pushes to the running AVD, points the SAF picker at the right tree
scripts/fetch-test-audiobooks.sh --push    # fetch + push in one shot
```

Tracks land at `/sdcard/Audiobooks/whisperboy-test/` on the device. After pushing,
open whisperboy, go through the onboarding flow, and pick that folder as the
library root in `Author` mode — exercises the multi-author + multi-book scan
path.

The two test books (verify URLs at fetch time, LibriVox is public domain in the
US):

- **The Gift of the Magi — O. Henry** — single-folder book, multiple chapter files, embedded cover art
- **The Hound of the Baskervilles — Arthur Conan Doyle** — single M4B file with embedded chapter markers (tests the MP4 box parser path)

`test-audiobooks/` is gitignored — re-fetch on a fresh checkout via the script.

## Test

See [`CLAUDE.md`](CLAUDE.md) for the full Claude-driven test loop.

- **Unit / data layer** — Robolectric, JVM-only, zero device.
- **UI / integration** — [`mobile-mcp`](https://github.com/mobile-next/mobile-mcp) over ADB driving the headless AVD (or a real phone via wifi-adb).

## Acknowledgements

Whisperboy owes its feature surface and a fair bit of its UI vocabulary to
[Voice](https://github.com/PaulWoitaschek/Voice) by Paul Woitaschek (GPLv3).
We don't fork or vendor any Voice code — whisperboy is written from scratch, MIT-licensed —
but the design space Voice mapped out (SAF-only, four folder modes, sleep timer
with fade + shake, custom MP4 chapter parser, MediaLibraryService for Auto)
is the design space whisperboy starts in.

## Translations

Translations are produced by the user + Claude per-language; English (`app/src/main/res/values/strings.xml`) is canonical and missing keys fall back to English at runtime. The table below is regenerated by `scripts/translation-progress.sh`.

<!-- TRANSLATIONS-START -->

| Language | Coverage | Status |
| --- | --- | --- |
| [German](app/src/main/res/values-de/strings.xml) | 328/328 (100%) | complete |

<!-- TRANSLATIONS-END -->

## License

MIT. See [`LICENSE`](LICENSE).
