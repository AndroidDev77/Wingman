#!/usr/bin/env bash
# Build the Android debug APK and run JVM unit tests. Requires the Android SDK.
set -euo pipefail
cd "$(dirname "$0")/../android"
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] && [[ ! -f local.properties ]]; then
  echo "Android SDK not found: set ANDROID_HOME or create android/local.properties (sdk.dir=...)." >&2
  echo "Result: UNVERIFIED" >&2
  exit 2
fi
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
