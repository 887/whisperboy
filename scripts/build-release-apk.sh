#!/usr/bin/env bash
# build-release-apk.sh — build a release APK into release/ for distribution.
#
# Three actions, in order:
#   1. ./scripts/build-release-apk.sh                  # build APK into release/
#   2. ./scripts/build-release-apk.sh --gh-release     # build + upload to GitHub Releases
#   3. ./scripts/build-release-apk.sh --install        # build + adb install onto connected device
#
# Combine: --gh-release --install is fine (this is the "phone-vibing" happy path).
#
# --skip-baseline: skip the `:app:generateBaselineProfile` step that normally
# runs before the assemble. By default the script regenerates the Baseline
# Profile against a connected AVD/device — needed for the ~25–35% cold-start
# win to stay fresh. Pass --skip-baseline for dev builds without a device
# attached; the last-committed app/src/main/baseline-prof.txt will be reused.
#
# Output: release/whisperboy-<version>-<sha7>.apk and a release/latest.apk symlink.
#
# Signing: this uses Gradle's debug keystore by default (good for personal
# sideload). For a real published release, set WHISPER_RELEASE_KEYSTORE +
# WHISPER_RELEASE_KEY_ALIAS + WHISPER_RELEASE_KEY_PASSWORD env vars and the
# script picks them up.
#
# What --gh-release does:
#   - Creates the GitHub Release `v<version>-<sha7>` with the APK attached.
#   - Generates release notes from `git log <prev-tag>..HEAD`.
#   - Includes a "Verify build" section with the commit SHA + APK SHA-256.
#   - Pushes the local annotated tag to `origin` (informational; the GH Action
#     fallback in `.github/workflows/release.yml` is self-disabling and will
#     exit 0 when an APK is already attached, so no CI minutes burned).

set -euo pipefail

ROOT="$(dirname "$(readlink -f "$0")")/.."
cd "${ROOT}"

# Toolchain — Android CLI's bundled JRE 21 lacks java.rmi, so use system JDK
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

PUSH_TO_GH=false
INSTALL_TO_DEVICE=false
SKIP_BASELINE=false
for arg in "$@"; do
    case "$arg" in
        --gh-release|--github)   PUSH_TO_GH=true ;;
        --install|--adb-install) INSTALL_TO_DEVICE=true ;;
        --skip-baseline)         SKIP_BASELINE=true ;;
        --help|-h)
            sed -n '1,/^$/p' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        *) echo "unknown flag: $arg" >&2; exit 1 ;;
    esac
done

BUILD_OK=false
GH_RELEASE_OK=false
INSTALL_OK=false

# Version + SHA tag for the artifact name
VERSION="$(awk -F'"' '/versionName/ {print $2; exit}' app/build.gradle.kts 2>/dev/null \
    || awk -F'=' '/versionName/ {print $2; exit}' app/build.gradle.kts \
    | tr -d ' "' || true)"
[ -z "${VERSION}" ] && VERSION="0.1.0-dev"
SHA7="$(git rev-parse --short=7 HEAD)"
SHA_FULL="$(git rev-parse HEAD)"
TAG="${VERSION}-${SHA7}"
REL_TAG="v${TAG}"

OUT_DIR="${ROOT}/release"
OUT_APK="${OUT_DIR}/whisperboy-${TAG}.apk"
mkdir -p "${OUT_DIR}"

# Decide release vs debug build flavour
if [ -n "${WHISPER_RELEASE_KEYSTORE:-}" ]; then
    echo "[build-release-apk] release-signed build (keystore: ${WHISPER_RELEASE_KEYSTORE})"
    GRADLE_TASK="assembleRelease"
    BUILD_APK="app/build/outputs/apk/release/whisperboy-release.apk"
else
    echo "[build-release-apk] debug-signed build (set WHISPER_RELEASE_KEYSTORE for production signing)"
    GRADLE_TASK="assembleDebug"
    BUILD_APK="app/build/outputs/apk/debug/whisperboy-debug.apk"
fi

# cold-start-perf F.4 — regenerate the Baseline Profile before the APK
# build so every shipped APK ships with a fresh ART profile. This step
# requires a connected device or AVD (the macrobenchmark in
# :baselineprofile records the cold-boot path on real hardware); use
# --skip-baseline to fall back to the committed app/src/main/baseline-prof.txt
# when no device is connected (e.g. quick dev builds, CI fallbacks).
if "${SKIP_BASELINE}"; then
    echo "[build-release-apk] --skip-baseline set; using committed app/src/main/baseline-prof.txt"
else
    echo "[build-release-apk] generating baseline profile (this may take 2-3 min)..."
    if ! ./gradlew :app:generateBaselineProfile --console=plain; then
        echo "[build-release-apk] generateBaselineProfile failed." >&2
        echo "[build-release-apk] common cause: no connected device. Start the AVD:" >&2
        echo "[build-release-apk]   scripts/start-avd.sh" >&2
        echo "[build-release-apk] or rerun with --skip-baseline to reuse the committed profile." >&2
        exit 1
    fi
fi

echo "[build-release-apk] running ./gradlew :app:${GRADLE_TASK}..."
./gradlew ":app:${GRADLE_TASK}" --console=plain >/dev/null

if [ ! -f "${BUILD_APK}" ]; then
    echo "[build-release-apk] expected APK at ${BUILD_APK} but it's missing" >&2
    exit 1
fi

cp "${BUILD_APK}" "${OUT_APK}"
ln -sf "$(basename "${OUT_APK}")" "${OUT_DIR}/latest.apk"
APK_SIZE="$(du -h "${OUT_APK}" | cut -f1)"
APK_SHA256="$(sha256sum "${OUT_APK}" | awk '{print $1}')"
echo "[build-release-apk] ${OUT_APK} (${APK_SIZE})"
echo "[build-release-apk] sha256: ${APK_SHA256}"
echo "[build-release-apk] symlink: ${OUT_DIR}/latest.apk"
BUILD_OK=true

# Refresh README Translations table before tagging the release (when the helper exists).
if [ -x "${ROOT}/scripts/translation-progress.sh" ]; then
    "${ROOT}/scripts/translation-progress.sh" --update
    if ! git -C "${ROOT}" diff --quiet README.md 2>/dev/null; then
        echo "[build-release-apk] README.md Translations table refreshed"
    fi
fi

# --gh-release: create or amend a tag-versioned GitHub release
if "${PUSH_TO_GH}"; then
    if ! command -v gh >/dev/null; then
        echo "[build-release-apk] gh CLI not found — install with 'pacman -S github-cli' or equivalent" >&2
        exit 1
    fi

    PREV_TAG="$(git tag -l 'v*' --sort=-creatordate | grep -v "^${REL_TAG}\$" | head -n1 || true)"
    if [ -n "${PREV_TAG}" ]; then
        COMMIT_RANGE="${PREV_TAG}..HEAD"
        CHANGELOG_HEADER="Changes since \`${PREV_TAG}\`"
    else
        COMMIT_RANGE="HEAD"
        CHANGELOG_HEADER="Recent changes"
    fi
    COMMIT_LIST="$(git log "${COMMIT_RANGE}" --pretty=format:'- %s (%h)' | head -n 50)"
    [ -z "${COMMIT_LIST}" ] && COMMIT_LIST="- (no commits since previous tag)"

    NOTES_FILE="$(mktemp)"
    cat >"${NOTES_FILE}" <<EOF
Auto-built APK from commit \`${SHA7}\`.

## Install

Sideload via [Obtainium](https://github.com/ImranR98/Obtainium) (recommended — see the project README for setup) or install directly:

\`\`\`
adb install -r whisperboy-${TAG}.apk
\`\`\`

## ${CHANGELOG_HEADER}

${COMMIT_LIST}

## Verify build

| Field | Value |
| --- | --- |
| Commit | \`${SHA_FULL}\` |
| APK | \`whisperboy-${TAG}.apk\` |
| APK SHA-256 | \`${APK_SHA256}\` |
| Build flavour | \`${GRADLE_TASK}\` |

To verify locally:

\`\`\`
sha256sum whisperboy-${TAG}.apk
# expected: ${APK_SHA256}
\`\`\`
EOF

    if gh release view "${REL_TAG}" >/dev/null 2>&1; then
        echo "[build-release-apk] release ${REL_TAG} already exists, refreshing notes + APK..."
        gh release upload "${REL_TAG}" "${OUT_APK}" --clobber
        gh release edit "${REL_TAG}" --notes-file "${NOTES_FILE}" >/dev/null
    else
        echo "[build-release-apk] creating release ${REL_TAG} on GitHub..."
        gh release create "${REL_TAG}" "${OUT_APK}" \
            --title "whisperboy ${VERSION} (${SHA7})" \
            --notes-file "${NOTES_FILE}"
    fi
    rm -f "${NOTES_FILE}"

    REL_URL="$(gh release view "${REL_TAG}" --json url -q .url)"
    echo "[build-release-apk] ${REL_URL}"

    if git rev-parse -q --verify "refs/tags/${REL_TAG}" >/dev/null; then
        echo "[build-release-apk] pushing tag ${REL_TAG} to origin..."
        git push origin "refs/tags/${REL_TAG}" || \
            echo "[build-release-apk] tag push failed (may already exist on remote — continuing)"
    else
        echo "[build-release-apk] note: gh created the release tag remotely; fetching..."
        git fetch origin "refs/tags/${REL_TAG}:refs/tags/${REL_TAG}" 2>/dev/null || true
    fi
    GH_RELEASE_OK=true
fi

# --install: push to a connected ADB device
if "${INSTALL_TO_DEVICE}"; then
    if ! command -v adb >/dev/null; then
        echo "[build-release-apk] adb not on PATH" >&2; exit 1
    fi
    if ! adb devices | grep -qE '\<device$'; then
        echo "[build-release-apk] no ADB device connected" >&2
        echo "[build-release-apk] start the AVD: scripts/start-avd.sh" >&2
        echo "[build-release-apk] or wifi-adb-pair your phone first" >&2
        exit 1
    fi
    echo "[build-release-apk] adb install -r ${OUT_APK}..."
    adb install -r "${OUT_APK}"
    echo "[build-release-apk] installed"
    INSTALL_OK=true
fi

echo "[build-release-apk] ----- summary -----"
echo "[build-release-apk] build:       $($BUILD_OK && echo OK || echo FAIL)"
if "${PUSH_TO_GH}"; then
    echo "[build-release-apk] gh release: $($GH_RELEASE_OK && echo OK || echo FAIL)"
fi
if "${INSTALL_TO_DEVICE}"; then
    echo "[build-release-apk] install:    $($INSTALL_OK && echo OK || echo FAIL)"
fi
"${BUILD_OK}" || { echo "[build-release-apk] build step did not complete" >&2; exit 1; }
if "${PUSH_TO_GH}" && ! "${GH_RELEASE_OK}"; then
    echo "[build-release-apk] gh release step did not complete" >&2; exit 1
fi
if "${INSTALL_TO_DEVICE}" && ! "${INSTALL_OK}"; then
    echo "[build-release-apk] install step did not complete" >&2; exit 1
fi

echo "[build-release-apk] done"
