#!/usr/bin/env bash
# Build the Android debug APK and run JVM unit tests. Requires the Android SDK.
set -euo pipefail
cd "$(dirname "$0")/../android"
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] && [[ ! -f local.properties ]]; then
  echo "Android SDK not found: set ANDROID_HOME or create android/local.properties (sdk.dir=...)." >&2
  echo "Result: UNVERIFIED" >&2
  exit 2
fi
# Default build (no experimental code): testDebugUnitTest also runs checkNoExperimentalClasses, which
# fails if any experimental connector class was compiled (D-023, D-026, WING-031).
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
# WING-031: the experimental Hinge connector lives in src/experimental and src/testExperimental, which
# only the experimental build compiles. Build and test that variant too so it cannot rot unnoticed.
# It never produces an app bundle (the guard in app/build.gradle.kts) and nothing from it is shipped.
./gradlew --no-daemon -Pwingman.experimental=true :app:assembleDebug :app:testDebugUnitTest
