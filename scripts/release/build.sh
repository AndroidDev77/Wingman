#!/usr/bin/env bash
# WING-026: build the private-beta artifacts for the version in scripts/release/version.env.
#
#   scripts/release/build.sh [--dry-run] [--skip-image] [--skip-apk]
#                            [--api-base-url URL] [--allow-dirty]
#                            [--no-auth0] [-Pwingman.oidc.<key>=<value> ...]
#
# Produces, under build/release/<version>/ (git-ignored):
#   wingman-<version>-debug.apk     the tester APK: DEBUG build type (it keeps the dev-token
#                                   sign-in panel as a fallback), talking to the real API
#                                   (-Pwingman.useMockApi=false), versionName=<version>,
#                                   versionCode=<code>, and Auth0 sign-in (I-014) from
#                                   config/beta/auth0.gradle.properties
#   SHA256SUMS                      checksum of the APK, to give testers with the file
#   BUILD_INFO                      version, code, git commit, image tag, API base URL, Auth0 issuer
# and the backend image  wingman-backend:<version>-<short sha>  (local Docker; push it to
# your registry yourself, see docs/beta/release_checklist.md).
#
# Versions go in through Gradle properties and an init script (version.init.gradle); no
# Gradle file is edited. --api-base-url bakes the backend URL into BuildConfig.API_BASE_URL
# (default: the build file's http://10.0.2.2:8000/, the emulator's host loopback). Debug
# builds allow cleartext only to 10.0.2.2/localhost/127.0.0.1, so any other host must be https.
#
# Auth0 (I-014): every wingman.oidc.* line of config/beta/auth0.gradle.properties (committed,
# public identifiers, no secrets; docs/auth0.md) is passed as -P<key>=<value>. A
# -Pwingman.oidc.<key>=<value> given here replaces that key's value; --no-auth0 passes none
# (the APK then has only the dev-token panel). Only this script reads that file: plain
# ./gradlew, scripts/android-build.sh, CI and E2E builds never get these values.
#
# Refuses a dirty work tree (the commit in BUILD_INFO must be what was built) unless
# --allow-dirty. Run scripts/release/check.sh first; this script does not run the tests.
# Env: WINGMAN_RELEASE_VERSION_FILE and WINGMAN_RELEASE_REPO (tests), WINGMAN_RELEASE_OUT (output dir),
#      WINGMAN_RELEASE_AUTH0_PROPS (Auth0 properties file; default config/beta/auth0.gradle.properties).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="${WINGMAN_RELEASE_REPO:-$(cd "$here/../.." && pwd)}"

dry=0 image=1 apk=1 dirty_ok=0 api_url="" api_url_set=0 auth0=1
oidc_overrides=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry=1 ;;
    --skip-image) image=0 ;;
    --skip-apk) apk=0 ;;
    --allow-dirty) dirty_ok=1 ;;
    --api-base-url) api_url="${2:-}"; api_url_set=1; [[ $# -ge 2 ]] && shift ;;
    --no-auth0) auth0=0 ;;
    -Pwingman.oidc.*=*) oidc_overrides+=("${1#-P}") ;;
    -h|--help) sed -n '2,34p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

# Auth0 properties: data, not code. Only "wingman.oidc.<key>=<value>" lines, comments and blanks.
auth0_props="${WINGMAN_RELEASE_AUTH0_PROPS:-$here/../../config/beta/auth0.gradle.properties}"
oidc_keys="issuer clientId redirectUri audience connection scopes"
declare -A oidc=()
oidc_order=()
set_oidc() { # <key=value> <source>
  local key="${1%%=*}" value="${1#*=}"
  key="${key#wingman.oidc.}"
  if [[ " $oidc_keys " != *" $key "* ]]; then
    echo "build.sh: $2: unknown Auth0 property wingman.oidc.$key (allowed: $oidc_keys)" >&2
    exit 2
  fi
  if [[ -z "$value" || "$value" =~ [[:space:]] && "$key" != scopes ]]; then
    echo "build.sh: $2: wingman.oidc.$key must be a non-empty value without spaces" >&2
    exit 2
  fi
  [[ -v "oidc[$key]" ]] || oidc_order+=("$key")
  oidc[$key]="$value"
}
if [[ $auth0 -eq 1 ]]; then
  if [[ ! -f "$auth0_props" ]]; then
    echo "build.sh: Auth0 properties file not found: $auth0_props (or pass --no-auth0)" >&2
    exit 2
  fi
  n=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    n=$((n + 1))
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$line" =~ ^wingman\.oidc\.[A-Za-z]+= ]]; then
      echo "build.sh: $auth0_props:$n: expected wingman.oidc.<key>=<value>" >&2
      exit 2
    fi
    set_oidc "$line" "$auth0_props:$n"
  done <"$auth0_props"
  for o in "${oidc_overrides[@]}"; do set_oidc "$o" "command line"; done
  for k in issuer clientId redirectUri audience; do
    if [[ ! -v "oidc[$k]" ]]; then
      echo "build.sh: Auth0 sign-in needs wingman.oidc.$k ($auth0_props)" >&2
      exit 2
    fi
  done
  if [[ ! "${oidc[issuer]}" =~ ^https://[^/]+/$ ]]; then
    echo "build.sh: wingman.oidc.issuer must be https://<tenant-domain>/ with the trailing slash" >&2
    exit 2
  fi
elif [[ ${#oidc_overrides[@]} -gt 0 ]]; then
  echo "build.sh: -Pwingman.oidc.* cannot be combined with --no-auth0" >&2
  exit 2
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
for k in "${oidc_order[@]}"; do gradle_args+=("-Pwingman.oidc.$k=${oidc[$k]}"); done

echo "release:   $version (versionCode $code), commit $sha"
echo "image:     $([[ $image -eq 1 ]] && echo "$tag" || echo skipped)"
echo "apk:       $([[ $apk -eq 1 ]] && echo "$out/$apk_name" || echo skipped)"
echo "api url:   ${api_url:-http://10.0.2.2:8000/ (build default)}"
if [[ $auth0 -eq 1 ]]; then
  echo "sign-in:   Auth0 ${oidc[issuer]} client ${oidc[clientId]}${oidc[connection]:+ via ${oidc[connection]}} (+ dev-token panel)"
else
  echo "sign-in:   dev-token panel only (--no-auth0)"
fi

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
  if [[ $auth0 -eq 1 ]]; then
    echo "build_type=debug (Auth0 sign-in + dev-token panel)"
    echo "auth0_issuer=${oidc[issuer]}"
    echo "auth0_client_id=${oidc[clientId]}"
    echo "auth0_audience=${oidc[audience]}"
  else
    echo "build_type=debug (dev-token sign-in only, --no-auth0)"
  fi
  echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$out/BUILD_INFO"
echo "RELEASE BUILD DONE: $out"
