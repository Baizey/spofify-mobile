#!/usr/bin/env bash
# An isolated native UI run. Generated evidence is ignored; human screenshot review is mandatory.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"
BOOT_ONLY=false
SKIP_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --boot-smoke) BOOT_ONLY=true ;;
    --skip-build) SKIP_BUILD=true ;;
    *) printf 'Usage: %s [--boot-smoke] [--skip-build]\n' "$0" >&2; exit 2 ;;
  esac
done
BASE="$ROOT/.tooling/ui-testing"
RUN="$BASE/runs/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$RUN/screenshots" "$BASE/avd" "$BASE/android-home"
printf '%s\n' "$RUN" > "$BASE/latest-run.txt"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$ROOT/.tooling/android-sdk}}"
export ANDROID_AVD_HOME="$BASE/avd"
export ANDROID_USER_HOME="$BASE/android-home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.tooling/gradle}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PORT=5560
SERIAL="emulator-$PORT"
AVD=spofify_visual_test
for tool in "$ADB" "$EMU" "$AVDMANAGER"; do
  [[ -x "$tool" ]] || { printf 'Missing Android tool: %s\nEvidence: %s\n' "$tool" "$RUN" >&2; exit 1; }
done
if ! "$SKIP_BUILD"; then
  tasks=(:app:assembleDebug)
  "$BOOT_ONLY" || tasks+=(:app:assembleDebugAndroidTest)
  ./gradlew "${tasks[@]}" --no-daemon --console=plain > "$RUN/build.log" 2>&1 || {
    printf 'Build failed; see %s/build.log\n' "$RUN" >&2; exit 1;
  }
fi
# AGP versions can publish under outputs/ or intermediates/. Resolve the latest actual
# artifact instead of accidentally installing a stale APK left at an older conventional path.
locate_apk() {
  python3 - "$ROOT/app/build" "$1" <<'PY'
import pathlib, sys
paths = list(pathlib.Path(sys.argv[1]).rglob(sys.argv[2]))
if paths:
    print(max(paths, key=lambda p: p.stat().st_mtime_ns))
PY
}
APK=$(locate_apk app-debug.apk)
TEST_APK=$(locate_apk app-debug-androidTest.apk)
[[ -n "$APK" && -f "$APK" ]] || { printf 'Build the debug APK first.\n' >&2; exit 1; }
if ! "$BOOT_ONLY" && [[ -z "$TEST_APK" || ! -f "$TEST_APK" ]]; then
  printf 'Build :app:assembleDebugAndroidTest first.\n' >&2; exit 1
fi
# Choose an installed image; do not download packages or alter an existing personal AVD.
IMAGE="${UI_TEST_SYSTEM_IMAGE:-}"
if [[ -z "$IMAGE" ]]; then
  for candidate in 'system-images;android-36;google_apis;x86_64' 'system-images;android-37.0;google_apis_playstore;x86_64'; do
    path=${candidate//;/\/}
    if [[ -f "$ANDROID_HOME/$path/source.properties" ]]; then IMAGE="$candidate"; break; fi
  done
fi
[[ -n "$IMAGE" ]] || { printf 'No supported image installed. Set UI_TEST_SYSTEM_IMAGE after coordinating SDK installation.\n' >&2; exit 1; }
if [[ ! -f "$BASE/avd/$AVD.ini" ]]; then
  printf 'no\n' | "$AVDMANAGER" create avd --name "$AVD" --package "$IMAGE" --device pixel_7 \
    --path "$BASE/avd/$AVD.avd" > "$RUN/avd-create.log" 2>&1
  touch "$BASE/avd/$AVD.avd/.spofify-ui-owned"
fi
[[ -f "$BASE/avd/$AVD.avd/.spofify-ui-owned" ]] || {
  printf 'Refusing an AVD without the isolated test ownership marker.\n' >&2; exit 1;
}
# Never target a personal/previously running device by accident.
if "$ADB" devices | grep -q "^$SERIAL[[:space:]]"; then
  printf '%s is already in use; stop it deliberately before this isolated run.\n' "$SERIAL" >&2; exit 1
fi
ACCEL=off
[[ -r /dev/kvm && -w /dev/kvm ]] && ACCEL=on
{
  printf '# Native UI run\n\n- Started (UTC): %s\n- Serial: %s\n- Image: %s\n- Acceleration: %s\n' "$(date -u +%FT%TZ)" "$SERIAL" "$IMAGE" "$ACCEL"
  printf -- '- Git HEAD: %s (see working-tree.txt for local changes)\n' "$(git rev-parse HEAD)"
  printf -- '- APK SHA-256: %s\n- Visual review: **PENDING**\n' "$(sha256sum "$APK" | cut -d ' ' -f 1)"
} > "$RUN/evidence.md"
git status --short > "$RUN/working-tree.txt"
EMU_PID=
cleanup() {
  if [[ -n "$EMU_PID" ]]; then
    kill "$EMU_PID" 2>/dev/null || true
    wait "$EMU_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM
"$EMU" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot \
  -accel "$ACCEL" -gpu swiftshader -memory 2048 -cores 2 > "$RUN/emulator.log" 2>&1 &
EMU_PID=$!
ready=false
DEADLINE=$((SECONDS + ${UI_TEST_BOOT_TIMEOUT:-240}))
while (( SECONDS < DEADLINE )); do
  if ! kill -0 "$EMU_PID" 2>/dev/null; then break; fi
  boot=$(timeout 5 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then ready=true; break; fi
  sleep 3
done
if ! "$ready"; then
  printf '\nNative UI validation BLOCKED: emulator did not boot. See emulator.log; no visual pass is claimed.\n' >> "$RUN/evidence.md"
  printf 'Emulator did not boot. UI validation BLOCKED. Evidence: %s\n' "$RUN" >&2
  exit 1
fi
# These settings affect ONLY the isolated synthetic-data AVD. On-device fixtures use loopback.
"$ADB" -s "$SERIAL" shell settings put global device_provisioned 1
"$ADB" -s "$SERIAL" shell settings put secure user_setup_complete 1
"$ADB" -s "$SERIAL" shell settings put system font_scale 1.0
"$ADB" -s "$SERIAL" shell svc wifi disable || true
"$ADB" -s "$SERIAL" shell svc data disable || true
"$ADB" -s "$SERIAL" shell input keyevent 82
{
  printf '\n## Device\n\n```\n'
  "$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk
  "$ADB" -s "$SERIAL" shell wm size
  "$ADB" -s "$SERIAL" shell wm density
  printf '```\n'
} >> "$RUN/evidence.md"
"$ADB" -s "$SERIAL" install -r "$APK" > "$RUN/install.log" 2>&1
"$ADB" -s "$SERIAL" shell pm clear dev.spofify.mobile >> "$RUN/install.log"
if "$BOOT_ONLY"; then
  "$ADB" -s "$SERIAL" shell am start -W -n dev.spofify.mobile/.MainActivity > "$RUN/activity.log"
  sleep 4
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$RUN/screenshots/00-sign-in.png"
  printf '\nLogin launch only; full native journey NOT run. Screenshot review PENDING.\n' >> "$RUN/evidence.md"
else
  "$ADB" -s "$SERIAL" install -r "$TEST_APK" >> "$RUN/install.log" 2>&1
  set +e
  timeout 300 "$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class dev.spofify.mobile.ui.NativeUiSmokeTest \
    dev.spofify.mobile.test/androidx.test.runner.AndroidJUnitRunner > "$RUN/instrumentation.log" 2>&1
  result=$?
  set -e
  "$ADB" -s "$SERIAL" pull /sdcard/Android/data/dev.spofify.mobile/files/ui-evidence "$RUN/screenshots/native" > "$RUN/pull.log" 2>&1 || true
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$RUN/screenshots/99-final.png" || true
  "$ADB" -s "$SERIAL" logcat -d -t 1000 > "$RUN/logcat.txt" || true
  if [[ "$result" != 0 ]] || ! grep -Eq 'OK \([0-9]+ tests?\)' "$RUN/instrumentation.log"; then
    printf '\nNative journey FAILED or did not finish. Visual review PENDING; inspect partial screenshots.\n' >> "$RUN/evidence.md"
    printf 'Native UI journey failed. Evidence: %s\n' "$RUN" >&2; exit 1
  fi
  printf '\nNative instrumentation journey passed. Visual review is STILL REQUIRED.\n' >> "$RUN/evidence.md"
fi
printf 'Native run captured: %s\nInspect the screenshots and record findings before claiming visual validation.\n' "$RUN"
