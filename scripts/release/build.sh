#!/usr/bin/env bash
# WING-026: build the private-beta artifacts for the version in scripts/release/version.env.
#
#   scripts/release/build.sh [--dry-run] [--skip-image] [--skip-apk]
#                            [--api-base-url URL] [--allow-dirty]
#
# Produces, under build/release/<version>/ (git-ignored):
#   wingman-<version>-debug.apk     the tester APK: DEBUG build type (the only one with the
#                                   dev-token sign-in panel, because no IdP exists, I-014),
#                                   talking to the real API (-Pwingman.useMockApi=false),
#                                   versionName=<version>, versionCode=<code>
#   SHA256SUMS                      checksum of the APK, to give testers with the file
#   BUILD_INFO                      version, code, git commit, image tag, API base URL
# and the backend image  wingman-backend:<version>-<short sha>  (local Docker; push it to
# your registry yourself, see docs/beta/release_checklist.md).
#
# Versions go in through Gradle properties and an init script (version.init.gradle); no
# Gradle file is edited. --api-base-url bakes the backend URL into BuildConfig.API_BASE_URL
# (default: the build file's http://10.0.2.2:8000/, the emulator's host loopback). Debug
# builds allow cleartext only to 10.0.2.2/localhost/127.0.0.1, so any other host must be https.
#
# Refuses a dirty work tree (the commit in BUILD_INFO must be what was built) unless
# --allow-dirty. Run scripts/release/check.sh first; this script does not run the tests.
# Env: WINGMAN_RELEASE_VERSION_FILE and WINGMAN_RELEASE_REPO (tests), WINGMAN_RELEASE_OUT (output dir).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="${WINGMAN_RELEASE_REPO:-$(cd "$here/../.." && pwd)}"

dry=0 image=1 apk=1 dirty_ok=0 api_url="" api_url_set=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry=1 ;;
    --skip-image) image=0 ;;
    --skip-apk) apk=0 ;;
    --allow-dirty) dirty_ok=1 ;;
    --api-base-url) api_url="${2:-}"; api_url_set=1; [[ $# -ge 2 ]] && shift ;;
    -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "build.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

read -r version code < <("$here/version.sh" show)

if [[ $api_url_set -eq 1 ]]; then
  if [[ ! "$api_url" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?/([A-Za-z0-9._~/-]*)?$ ]]; then
    echo "build.sh: --api-base-url must look like http(s)://host[:port]/ with a trailing slash" >&2
    exit 2
  fi
  host="$(sed -E 's#^https?://([^:/]+).*#\1#' <<<"$api_url")"
  if [[ "$api_url" == http://* && "$host" != "10.0.2.2" && "$host" != "localhost" && "$host" != "127.0.0.1" ]]; then
    echo "build.sh: plain http is allowed only to 10.0.2.2, localhost or 127.0.0.1 (debug network-security config); use https://$host/" >&2
    exit 2
  fi
fi

sha="$(git -C "$repo" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
if [[ $dirty_ok -eq 0 && -n "$(git -C "$repo" status --porcelain 2>/dev/null)" ]]; then
  echo "build.sh: the work tree has uncommitted changes; commit them or pass --allow-dirty" >&2
  exit 2
fi
tag="wingman-backend:${version}-${sha}"
out="${WINGMAN_RELEASE_OUT:-$repo/build/release/$version}"
apk_name="wingman-${version}-debug.apk"

gradle_args=(--no-daemon --init-script "$here/version.init.gradle" :app:assembleDebug
  -Pwingman.useMockApi=false "-Pwingman.versionName=$version" "-Pwingman.versionCode=$code")
[[ -n "$api_url" ]] && gradle_args+=("-Pwingman.apiBaseUrl=$api_url")

echo "release:   $version (versionCode $code), commit $sha"
echo "image:     $([[ $image -eq 1 ]] && echo "$tag" || echo skipped)"
echo "apk:       $([[ $apk -eq 1 ]] && echo "$out/$apk_name" || echo skipped)"
echo "api url:   ${api_url:-http://10.0.2.2:8000/ (build default)}"

if [[ $dry -eq 1 ]]; then
  [[ $image -eq 1 ]] && echo "would run: docker build -t $tag $repo/backend"
  [[ $apk -eq 1 ]] && echo "would run: (cd $repo/android && ./gradlew ${gradle_args[*]})"
  echo "dry run: nothing built"
  exit 0
fi

mkdir -p "$out"
if [[ $image -eq 1 ]]; then
  docker build -t "$tag" "$repo/backend"
fi
if [[ $apk -eq 1 ]]; then
  if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" && ! -f "$repo/android/local.properties" ]]; then
    echo "build.sh: Android SDK not found (set ANDROID_HOME or android/local.properties)" >&2
    exit 2
  fi
  (cd "$repo/android" && ./gradlew "${gradle_args[@]}")
  cp "$repo/android/app/build/outputs/apk/debug/app-debug.apk" "$out/$apk_name"
  (cd "$out" && sha256sum "$apk_name" > SHA256SUMS)
fi
{
  echo "version=$version"
  echo "version_code=$code"
  echo "commit=$sha"
  echo "image=$([[ $image -eq 1 ]] && echo "$tag" || echo skipped)"
  echo "apk=$([[ $apk -eq 1 ]] && echo "$apk_name" || echo skipped)"
  echo "api_base_url=${api_url:-http://10.0.2.2:8000/}"
  echo "build_type=debug (dev-token sign-in; no IdP, I-014)"
  echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$out/BUILD_INFO"
echo "RELEASE BUILD DONE: $out"
