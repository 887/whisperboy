#!/usr/bin/env bash
# Phase D.6 library smoke test — end-to-end exercise of D.1 (schema) +
# D.2 (SAF scanner) + D.3 (MediaAnalyzer enrichment) + D.4 (applyScan +
# CoverStore) + D.5 (rescan coordinator) in one run.
#
# What it does:
#   1. Generates two synthetic test books with ffmpeg:
#        - SingleFolder-shaped: 3-chapter folder of mp3s
#        - SingleFile-shaped:   1 m4b file (one chapter for now;
#          embedded chapter parsing lands in Phase I)
#   2. Pushes them into /sdcard/Audiobooks/whisperboy-test/ on the
#      connected device. Whisperboy's onboarding picker only sees what
#      the user opts into via SAF — so the layout matters: each book is
#      a first-level child under the picked tree, FolderType.Root.
#   3. Wipes app data so the coordinator's cold-start scan fires when
#      the picker hands back the tree URI.
#   4. Installs the latest debug APK, launches WhisperboyActivity.
#   5. Drives the SAF picker via uiautomator (dump → tap-by-text):
#      taps "Pick an audiobook folder", navigates the picker into
#      Audiobooks/whisperboy-test, taps "USE THIS FOLDER" / "ALLOW",
#      then taps "Root" in the FolderType bottom sheet.
#   6. Polls logcat for the SMOKE_TAG line emitted by
#      AndroidLibraryRescanCoordinator (D.6 instrumentation).
#   7. Asserts roots=1 books=2 chapters=4.
#
# Why uiautomator instead of skipping the picker: SAF persistable URI
# permissions can ONLY be granted by the system picker — there is no
# `pm grant` shortcut for them. The picker walk is the test.
#
# Requirements: ffmpeg, adb, the latest debug APK (built on demand
# below if missing), and a target reachable as `adb devices`. Tested
# against `emulator-5554` (API 36, com.google.android.documentsui).
#
# Usage:
#   scripts/library-smoke-test.sh
#   ADB_DEVICE=emulator-5556 scripts/library-smoke-test.sh
set -euo pipefail

ROOT="$(dirname "$(readlink -f "$0")")/.."
cd "${ROOT}"

# Toolchain: honour JAVA_HOME if set, else pick the newest installed full JDK
# (one with javac). Hardcoding a version broke when java-26 was replaced.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
    for _jdk in $(ls -d /usr/lib/jvm/java-*-openjdk 2>/dev/null | sort -V -r); do
        if [ -x "${_jdk}/bin/javac" ]; then export JAVA_HOME="${_jdk}"; break; fi
    done
fi
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

APP_ID="com.eight87.whisperboy"
ACTIVITY="${APP_ID}/.WhisperboyActivity"
APK="app/build/outputs/apk/debug/whisperboy-debug.apk"
SMOKE_TAG="whisperboy.scan"

LOCAL_FIXTURE_DIR="${TMPDIR:-/tmp}/whisperboy-lib-smoke"
REMOTE_FIXTURE_ROOT="/sdcard/Audiobooks/whisperboy-test"

DEVICE="${ADB_DEVICE:-}"
ADB=(adb)
[[ -n "$DEVICE" ]] && ADB+=( -s "$DEVICE" )

require_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 2; }; }
require_cmd ffmpeg
require_cmd adb

# --- helpers -----------------------------------------------------------

dump_ui() {
  # Dumps the current UI hierarchy from the device and prints it to stdout.
  # `uiautomator dump` writes to a path on the device; we cat it back.
  "${ADB[@]}" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || return 1
  "${ADB[@]}" shell cat /sdcard/window_dump.xml 2>/dev/null
}

# Find the bounds attribute of a node containing the given text (case-sensitive,
# substring match) in the latest UI dump and tap its center. Returns 0 on tap,
# 1 if not found. Used for picker walk and bottom-sheet selections.
tap_text() {
  local needle="$1"
  local xml; xml="$(dump_ui)" || return 1
  # uiautomator XML lines are huge; grep for the node, then extract bounds.
  local line
  line="$(echo "$xml" | tr '>' '\n' | grep -F "text=\"${needle}\"" | head -n1)" || true
  [[ -z "$line" ]] && return 1
  local bounds
  bounds="$(echo "$line" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -n1)"
  [[ -z "$bounds" ]] && return 1
  # bounds="[x1,y1][x2,y2]"
  local nums; nums="$(echo "$bounds" | grep -oE '[0-9]+' | tr '\n' ' ')"
  read -r x1 y1 x2 y2 <<< "$nums"
  local cx=$(( (x1 + x2) / 2 ))
  local cy=$(( (y1 + y2) / 2 ))
  "${ADB[@]}" shell input tap "$cx" "$cy" >/dev/null
  return 0
}

# Wait until a UI node with the given text appears, up to `timeout` seconds.
wait_for_text() {
  local needle="$1"
  local timeout="${2:-15}"
  local i
  for i in $(seq 1 $((timeout * 2))); do
    local xml; xml="$(dump_ui 2>/dev/null)" || true
    if echo "$xml" | grep -qF "text=\"${needle}\""; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

cleanup() {
  echo "[cleanup] removing remote fixtures"
  "${ADB[@]}" shell rm -rf "$REMOTE_FIXTURE_ROOT" 2>/dev/null || true
}
trap cleanup EXIT

# --- 1. APK ------------------------------------------------------------

if [[ ! -f "$APK" ]]; then
  echo "[build] APK missing, running ./gradlew :app:assembleDebug"
  ./gradlew :app:assembleDebug --console=plain >/dev/null
fi

# --- 2. fixtures -------------------------------------------------------

echo "[fixtures] generating into $LOCAL_FIXTURE_DIR"
rm -rf "$LOCAL_FIXTURE_DIR"
mkdir -p "$LOCAL_FIXTURE_DIR/book-singlefolder"
mkdir -p "$LOCAL_FIXTURE_DIR/book-singlefile"

# SingleFolder book: 3 mp3 chapters.
for i in 1 2 3; do
  ffmpeg -y -loglevel error \
    -f lavfi -i "sine=frequency=$((400 + i * 50)):duration=1" \
    -metadata title="Chapter ${i}" \
    -metadata artist="Smoke Test Author" \
    -metadata album="Smoke Test Folder Book" \
    -ac 2 -b:a 96k \
    "$LOCAL_FIXTURE_DIR/book-singlefolder/0${i}-chapter-${i}.mp3"
done

# SingleFile book: one m4b (single chapter for now; embedded chapter
# markers land in Phase I).
ffmpeg -y -loglevel error \
  -f lavfi -i "sine=frequency=440:duration=2" \
  -metadata title="Smoke Test Single Book" \
  -metadata artist="Smoke Test Author" \
  -c:a aac -b:a 96k \
  "$LOCAL_FIXTURE_DIR/book-singlefile/the-book.m4b"

# --- 3. push -----------------------------------------------------------

echo "[push] $REMOTE_FIXTURE_ROOT"
"${ADB[@]}" shell rm -rf "$REMOTE_FIXTURE_ROOT" 2>/dev/null || true
"${ADB[@]}" shell mkdir -p "$REMOTE_FIXTURE_ROOT/book-singlefolder" >/dev/null
"${ADB[@]}" shell mkdir -p "$REMOTE_FIXTURE_ROOT/book-singlefile" >/dev/null
for f in "$LOCAL_FIXTURE_DIR/book-singlefolder/"*.mp3; do
  "${ADB[@]}" push "$f" "$REMOTE_FIXTURE_ROOT/book-singlefolder/" >/dev/null
done
"${ADB[@]}" push "$LOCAL_FIXTURE_DIR/book-singlefile/the-book.m4b" \
  "$REMOTE_FIXTURE_ROOT/book-singlefile/" >/dev/null

# --- 4. install + clean app state -------------------------------------

echo "[install] $APK"
"${ADB[@]}" install -r -d "$APK" >/dev/null
echo "[clear app data]"
"${ADB[@]}" shell pm clear "$APP_ID" >/dev/null
"${ADB[@]}" logcat -c
echo "[launch] $ACTIVITY"
"${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null
sleep 3

# --- 5. drive picker --------------------------------------------------

echo "[picker] tapping 'Pick an audiobook folder'"
if ! wait_for_text "Pick an audiobook folder" 10; then
  echo "[FAIL] empty-state picker button not found" >&2
  dump_ui >&2 | tail -20
  exit 1
fi
tap_text "Pick an audiobook folder" || { echo "tap failed" >&2; exit 1; }
sleep 2

# Picker open. May start in Recent or Downloads; navigate to whisperboy-test.
echo "[picker] navigating to Audiobooks → whisperboy-test"
# Open the hamburger / nav drawer to reveal storage roots
if wait_for_text "Show roots" 3; then
  tap_text "Show roots" || true
  sleep 1
fi

# Try tapping the device storage root (label varies by device — try several).
for label in "medium_phone" "Internal storage" "Phone" "Files"; do
  if tap_text "$label"; then
    sleep 1
    break
  fi
done

# Navigate into Audiobooks folder
if wait_for_text "Audiobooks" 5; then
  tap_text "Audiobooks" || true
  sleep 1
fi

# Navigate into whisperboy-test
if wait_for_text "whisperboy-test" 5; then
  tap_text "whisperboy-test" || true
  sleep 1
fi

echo "[picker] tapping 'USE THIS FOLDER'"
if ! wait_for_text "USE THIS FOLDER" 10; then
  # Some pickers use Title-case
  wait_for_text "Use this folder" 3 || {
    echo "[FAIL] 'USE THIS FOLDER' not visible" >&2
    exit 1
  }
fi
tap_text "USE THIS FOLDER" || tap_text "Use this folder" || true
sleep 2

echo "[picker] tapping 'ALLOW' on confirmation dialog"
if wait_for_text "ALLOW" 5; then
  tap_text "ALLOW" || tap_text "Allow" || true
elif wait_for_text "Allow" 3; then
  tap_text "Allow" || true
fi
sleep 2

# Folder type bottom sheet should appear.
echo "[picker] selecting 'Root' folder type"
if ! wait_for_text "Root" 10; then
  echo "[FAIL] FolderType sheet not visible — picker did not return" >&2
  exit 1
fi
tap_text "Root" || { echo "could not tap Root" >&2; exit 1; }

# --- 6. wait for the post-picker SCAN_COMPLETE ------------------------

# The cold-start scan (roots=0) fires immediately after launch; we want the
# *next* one — the root-set-change scan that runs after addRoot persists
# the picker's tree URI. Poll for a SCAN_COMPLETE line with roots>=1.
echo "[scan] waiting for post-picker SCAN_COMPLETE (roots>=1) in logcat (up to 30s)"
ready=0
scan_line=""
for _ in $(seq 1 60); do
  scan_line="$("${ADB[@]}" logcat -d -s "${SMOKE_TAG}":I 2>/dev/null \
    | grep "SCAN_COMPLETE" | grep -vE 'roots=0\b' | tail -n1 || true)"
  if [[ -n "$scan_line" ]]; then
    ready=1; break
  fi
  if "${ADB[@]}" logcat -d -s "${SMOKE_TAG}":E 2>/dev/null | grep -q "SCAN_FAILED"; then
    echo "[FAIL] SCAN_FAILED:" >&2
    "${ADB[@]}" logcat -d -s "${SMOKE_TAG}":E 2>/dev/null | tail -5 >&2
    exit 1
  fi
  sleep 0.5
done

if [[ $ready -ne 1 ]]; then
  echo "[FAIL] post-picker SCAN_COMPLETE not seen within 30s" >&2
  "${ADB[@]}" logcat -d -s "${SMOKE_TAG}":I AndroidRuntime:E 2>/dev/null | tail -30 >&2
  exit 1
fi

echo "[scan] $scan_line"

# --- 7. assert -------------------------------------------------------

# Expected: roots=1 books=2 chapters=4 (3 from SingleFolder + 1 from SingleFile)
expect="roots=1 books=2 chapters=4"
if ! grep -q "$expect" <<< "$scan_line"; then
  echo "[FAIL] expected '$expect' but got: $scan_line" >&2
  exit 1
fi

echo "[PASS] roots=1 books=2 chapters=4 — D.1+D.2+D.3+D.4+D.5 wire end-to-end"
