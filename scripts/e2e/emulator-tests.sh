#!/usr/bin/env bash
# WING-025: Android instrumented E2E tests on a RUNNING emulator, against a RUNNING backend.
# CI calls it from the android-emulator-runner step (.github/workflows/e2e.yml, job
# `emulator`); locally, start an emulator and `scripts/dev-run.sh` (with the dev auth
# stub) first. No physical device and no carrier is involved anywhere in this script.
#
#   scripts/e2e/emulator-tests.sh
#
# Env: WINGMAN_AUTH_DEV_SECRET (the backend's dev-stub secret; tokens are minted with the
#      backend CLI), WINGMAN_E2E_ARTIFACTS (default tests/e2e/.artifacts/emulator).
#
# Phase A  AppDrivesBackendE2eTest     app UI -> backend (sign-in, onboarding, inbox,
#                                      approve, audit timeline, takeover)
# Phase B  EmulatorSmsRoundTripTest    "emulator SMS stack exercised": default-SMS role via
#                                      `cmd role add-role-holder`, inbound via `adb emu sms
#                                      send`, reply via the app's SmsManager. NOT a device/carrier.
# Both phases always run; the script fails if either failed. Results and logcat go to the
# artifacts directory.
set -uo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
art="${WINGMAN_E2E_ARTIFACTS:-$repo/tests/e2e/.artifacts}/emulator"
mkdir -p "$art"
PKG=com.wingman
TEST_PKG=com.wingman.test
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
run_id="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
sms_from="5551234567"
sms_text="WING025-emulator-hello"

log() { printf '\n[%s] ==> %s\n' "$(date -u +%H:%M:%S)" "$*"; }

mint() {
  (cd "$repo/backend" && WINGMAN_ENV=dev WINGMAN_AUTH_DEV_STUB_ENABLED=true \
    uv run --quiet python -m app.security.dev_stub --sub "$1" --name "$2" | tail -n1)
}

log "emulator: $(adb shell getprop ro.product.model | tr -d '\r') API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), backend from the emulator: http://10.0.2.2:8000"
adb shell 'echo -n "health via 10.0.2.2: "; (toybox nc -w 3 10.0.2.2 8000 < /dev/null && echo reachable) || echo "nc unavailable"' || true
curl -fsS http://127.0.0.1:8000/health && echo

log "install debug app and test APKs (built with -Pwingman.useMockApi=false)"
adb install -r -g "$repo/android/app/build/outputs/apk/debug/app-debug.apk"
adb install -r -g "$repo/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb logcat -c || true

instrument() { # $1 class, $2 token, rest: extra -e pairs
  local cls="$1" token="$2"; shift 2
  adb shell am instrument -w \
    -e class "com.wingman.e2e.$cls" \
    -e wingman.token "$token" -e wingman.baseUrl "http://10.0.2.2:8000/" -e wingman.runId "$run_id" \
    "$@" "$RUNNER"
}

passed() { grep -q "^OK (" "$1" && ! grep -q "FAILURES!!!" "$1"; }

# ----------------------------------------------------------------------- phase A
log "phase A: AppDrivesBackendE2eTest (fresh user, fresh app data; app driven with UiAutomator)"
adb shell pm clear "$PKG" >/dev/null
token_a="$(mint "android-e2e-$run_id-a" "Android A")"
instrument AppDrivesBackendE2eTest "$token_a" | tee "$art/phaseA-instrument.txt"
a_ok=0; passed "$art/phaseA-instrument.txt" && a_ok=1
adb logcat -d > "$art/phaseA-logcat.txt" || true
grep -h "WING025-APP\|WING025-UI" "$art/phaseA-logcat.txt" > "$art/phaseA-observations.txt" || true
log "phase A observations (logcat lines prefixed WING025-APP / WING025-UI):"
cat "$art/phaseA-observations.txt"

# ----------------------------------------------------------------------- phase B
log "phase B: EmulatorSmsRoundTripTest (emulator SMS stack; NOT a physical device or carrier)"
adb shell pm clear "$PKG" >/dev/null
adb logcat -c || true
echo "telephony feature: $(adb shell pm list features | tr -d '\r' | grep -E 'android.hardware.telephony(\.messaging)?$' | tr '\n' ' ')" | tee "$art/phaseB-setup.txt"
echo "default SMS role holder before: $(adb shell cmd role get-role-holders android.app.role.SMS | tr -d '\r')" | tee -a "$art/phaseB-setup.txt"
for p in RECEIVE_SMS SEND_SMS READ_SMS; do adb shell pm grant "$PKG" "android.permission.$p" 2>&1 | tee -a "$art/phaseB-setup.txt"; done
# The role command can time out on a freshly booted emulator ("TimeoutException") while the
# role service is still busy; retry, and wait until the holder actually is com.wingman.
for attempt in 1 2 3 4 5 6; do
  echo "add-role-holder attempt $attempt:" | tee -a "$art/phaseB-setup.txt"
  adb shell cmd role add-role-holder android.app.role.SMS "$PKG" 2>&1 | tee -a "$art/phaseB-setup.txt"
  holder="$(adb shell cmd role get-role-holders android.app.role.SMS | tr -d '\r')"
  [[ "$holder" == "$PKG" ]] && break
  sleep 5
done
echo "default SMS role holder after: $(adb shell cmd role get-role-holders android.app.role.SMS | tr -d '\r')" | tee -a "$art/phaseB-setup.txt"

token_b="$(mint "android-e2e-$run_id-b" "Android B")"
instrument EmulatorSmsRoundTripTest "$token_b" -e wingman.sms true -e wingman.smsFrom "$sms_from" -e wingman.smsText "$sms_text" \
  > "$art/phaseB-instrument.txt" 2>&1 &
inst_pid=$!

# Wait for the test to pair and say it is ready, then send the SMS INTO the emulator.
sent=0
for _ in $(seq 1 120); do
  if adb shell run-as "$PKG" ls files/wing025-ready >/dev/null 2>&1; then
    log "test is ready: adb emu sms send $sms_from $sms_text"
    adb emu sms send "$sms_from" "$sms_text" 2>&1 | tee -a "$art/phaseB-setup.txt"
    sent=1
    break
  fi
  kill -0 "$inst_pid" 2>/dev/null || break
  sleep 2
done
[[ $sent -eq 1 ]] || echo "never saw the ready marker; adb emu sms send NOT executed" | tee -a "$art/phaseB-setup.txt"
wait "$inst_pid"
cat "$art/phaseB-instrument.txt"
b_ok=0; passed "$art/phaseB-instrument.txt" && b_ok=1
adb logcat -d > "$art/phaseB-logcat.txt" || true
grep -h "WING025-SMS\|WING025-UI" "$art/phaseB-logcat.txt" > "$art/phaseB-observations.txt" || true
log "phase B observations (logcat/instrumentation lines prefixed WING025-SMS):"
cat "$art/phaseB-observations.txt"
echo "system SMS provider (sent box, newest first; address and date only):"
adb shell "content query --uri content://sms/sent --projection address:date --sort 'date DESC'" 2>&1 | head -3 | tee -a "$art/phaseB-setup.txt" || true

log "summary: phaseA=$([[ $a_ok -eq 1 ]] && echo PASS || echo FAIL) phaseB(emulator SMS stack exercised)=$([[ $b_ok -eq 1 ]] && echo PASS || echo FAIL)"
printf 'phaseA=%s\nphaseB=%s\n' "$a_ok" "$b_ok" > "$art/summary.txt"
[[ $a_ok -eq 1 && $b_ok -eq 1 ]]
