#!/usr/bin/env bash
# WING-026: show or bump the beta release version (scripts/release/version.env).
#
#   scripts/release/version.sh show                 print "VERSION CODE"
#   scripts/release/version.sh bump beta|patch|minor [--dry-run]
#
#   beta   0.1.0-beta.1 -> 0.1.0-beta.2
#   patch  0.1.0-beta.3 -> 0.1.1-beta.1
#   minor  0.1.4-beta.2 -> 0.2.0-beta.1
# Every bump adds 1 to the Android versionCode. Only version.env is written: no Gradle file,
# no pyproject.toml, no app code. The version reaches the artifacts at build time
# (scripts/release/build.sh: image tag, APK versionName/versionCode via Gradle properties).
#
# Env: WINGMAN_RELEASE_VERSION_FILE overrides the file (tests).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
file="${WINGMAN_RELEASE_VERSION_FILE:-$here/version.env}"

die() { echo "version.sh: $*" >&2; exit 2; }
usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

[[ -f "$file" ]] || die "version file not found: $file"

read_key() { # $1 key -> value (no sourcing: the file is data, not code)
  local v
  v="$(grep -E "^$1=" "$file" | tail -n1 | cut -d= -f2-)"
  [[ -n "$v" ]] || die "$1 missing in $file"
  printf '%s' "$v"
}

version="$(read_key WINGMAN_RELEASE_VERSION)"
code="$(read_key WINGMAN_RELEASE_VERSION_CODE)"
re='^([0-9]+)\.([0-9]+)\.([0-9]+)-beta\.([1-9][0-9]*)$'
[[ "$version" =~ $re ]] || die "WINGMAN_RELEASE_VERSION '$version' is not X.Y.Z-beta.N"
major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}" beta="${BASH_REMATCH[4]}"
[[ "$code" =~ ^[1-9][0-9]{0,8}$ ]] || die "WINGMAN_RELEASE_VERSION_CODE '$code' is not a positive integer"

cmd="${1:-}"
case "$cmd" in
  show)
    echo "$version $code"
    ;;
  bump)
    kind="${2:-}"
    dry=0
    [[ "${3:-}" == "--dry-run" ]] && dry=1
    [[ $# -le 3 ]] || usage
    case "$kind" in
      beta)  new="$major.$minor.$patch-beta.$((beta + 1))" ;;
      patch) new="$major.$minor.$((patch + 1))-beta.1" ;;
      minor) new="$major.$((minor + 1)).0-beta.1" ;;
      *) usage ;;
    esac
    new_code=$((code + 1))
    echo "$version ($code) -> $new ($new_code)"
    if [[ $dry -eq 1 ]]; then
      echo "dry run: $file not changed"
      exit 0
    fi
    tmp="$(mktemp "$file.XXXXXX")"
    sed -e "s/^WINGMAN_RELEASE_VERSION=.*/WINGMAN_RELEASE_VERSION=$new/" \
        -e "s/^WINGMAN_RELEASE_VERSION_CODE=.*/WINGMAN_RELEASE_VERSION_CODE=$new_code/" "$file" > "$tmp"
    mv "$tmp" "$file"
    echo "wrote $file"
    ;;
  *)
    usage
    ;;
esac
