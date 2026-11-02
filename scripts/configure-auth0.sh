#!/usr/bin/env bash
# I-014: turn the three Auth0 values into backend env lines and the Android Gradle command.
#
#   scripts/configure-auth0.sh [--write] [--dry-run] [--check] [--env-file PATH]
#                              <tenant-domain> <client-id> <api-identifier> [connection]
#
#   tenant-domain   bare Auth0 domain, e.g. wingman.us.auth0.com (no https://, no slash)
#   client-id       the Native application's Client ID (public; there is no client secret)
#   api-identifier  the API's Identifier, e.g. https://api.wingman.app (the token audience)
#   connection      optional Auth0 connection to skip the login picker, e.g. google-oauth2
#
# Prints (always):
#   * the backend lines  WINGMAN_AUTH_ISSUER=https://<tenant>/  (trailing slash: Auth0's iss),
#     WINGMAN_AUTH_AUDIENCE, WINGMAN_AUTH_JWKS_URL=https://<tenant>/.well-known/jwks.json and
#     WINGMAN_AUTH_ALGORITHMS=RS256;
#   * the Gradle command (and ~/.gradle/gradle.properties lines) for the app build.
# Options:
#   --write          also put the backend lines into --env-file (default backend/.env), replacing
#                    any WINGMAN_AUTH_ISSUER/AUDIENCE/JWKS_URL/ALGORITHMS lines already there and
#                    keeping everything else (a .bak copy is kept). Refuses a file git does not
#                    ignore, so the values are never committed.
#   --dry-run        with --write: show what would be written, change nothing.
#   --check          fetch the JWKS URL with curl and check it lists an RS256 signing key. A
#                    failure (no network, sandbox proxy, wrong tenant) is reported as UNVERIFIED
#                    and makes the exit status 3; everything else has already been printed/written.
# Exit status: 0 ok, 2 bad arguments or refused write, 3 --check could not confirm the JWKS.
# Docs: docs/auth0.md. All values here are non-secret.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

usage() { sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "configure-auth0.sh: $*" >&2; exit 2; }

write=0 dry=0 check=0 env_file=""
args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --write) write=1 ;;
    --dry-run) dry=1 ;;
    --check) check=1 ;;
    --env-file)
      [[ $# -ge 2 ]] || die "--env-file needs a path"
      env_file="$2"; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; args+=("$@"); break ;;
    -*) die "unknown option: $1 (see --help)" ;;
    *) args+=("$1") ;;
  esac
  shift
done

if [[ ${#args[@]} -lt 3 || ${#args[@]} -gt 4 ]]; then
  usage >&2
  die "expected <tenant-domain> <client-id> <api-identifier> [connection]"
fi
domain="${args[0]}" client_id="${args[1]}" api_id="${args[2]}" connection="${args[3]:-}"

# ---------------------------------------------------------------- validation
case "$domain" in
  http://*|https://*) die "tenant domain must be the bare domain without a scheme, e.g. ${domain#*://}" ;;
esac
[[ "$domain" == */* ]] && die "tenant domain must not contain '/': give e.g. wingman.us.auth0.com"
[[ "$domain" =~ [A-Z] ]] && die "tenant domain must be lowercase, as the Auth0 dashboard shows it: ${domain,,}"
label='[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?'
[[ "$domain" =~ ^${label}(\.${label})+$ ]] || die "tenant domain does not look like a host name: '$domain'"

[[ -n "$client_id" ]] || die "client ID must not be empty"
[[ "$client_id" =~ ^[A-Za-z0-9_-]{1,128}$ ]] || die "client ID may contain only letters, digits, '-' and '_'"

[[ "$api_id" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/%-]*)?$ ]] \
  || die "API identifier must be URL-like, e.g. https://api.wingman.app (got '$api_id')"
[[ "$api_id" == "https://$domain/userinfo" ]] && die "API identifier must be your API's Identifier, not the /userinfo audience"

if [[ -n "$connection" && ! "$connection" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$ ]]; then
  die "connection must be a connection name such as google-oauth2"
fi

issuer="https://$domain/"
jwks_url="https://$domain/.well-known/jwks.json"
redirect="com.wingman.auth:/oauth2redirect"

env_lines() {
  echo "# Auth0 (I-014, docs/auth0.md). Written by scripts/configure-auth0.sh. Non-secret."
  echo "WINGMAN_AUTH_ISSUER=$issuer"
  echo "WINGMAN_AUTH_AUDIENCE=$api_id"
  echo "WINGMAN_AUTH_JWKS_URL=$jwks_url"
  echo "WINGMAN_AUTH_ALGORITHMS=RS256"
}

gradle_props=(
  "-Pwingman.useMockApi=false"
  "-Pwingman.oidc.issuer=$issuer"
  "-Pwingman.oidc.clientId=$client_id"
  "-Pwingman.oidc.redirectUri=$redirect"
  "-Pwingman.oidc.audience=$api_id"
)
[[ -n "$connection" ]] && gradle_props+=("-Pwingman.oidc.connection=$connection")

# ---------------------------------------------------------------- output
echo "== Backend env (backend/.env, or your deployment's secret/env store) =="
env_lines
echo
echo "== Android build =="
printf 'cd android && ./gradlew :app:assembleDebug'
printf ' %s' "${gradle_props[@]}"
printf '\n'
echo "# Same values in ~/.gradle/gradle.properties (also picked up by scripts/release/build.sh):"
for p in "${gradle_props[@]}"; do
  [[ "$p" == -Pwingman.useMockApi=* ]] && continue
  echo "${p#-P}"
done
echo "# Auth0 Native app: Allowed Callback URLs and Allowed Logout URLs = $redirect"

# ---------------------------------------------------------------- --write
if [[ $write -eq 1 ]]; then
  target="${env_file:-$repo/backend/.env}"
  target_dir="$(dirname "$target")"
  [[ -d "$target_dir" ]] || die "directory does not exist: $target_dir"
  if git -C "$target_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if ! git -C "$target_dir" check-ignore -q "$target"; then
      die "refusing to write $target: git does not ignore it, so the values could be committed"
    fi
    # The .bak copy holds the previous file (possibly real secrets): it must be ignored too.
    if [[ -f "$target" ]] && ! git -C "$target_dir" check-ignore -q "$target.bak"; then
      die "refusing to write $target: git does not ignore the backup $target.bak (add it to .gitignore)"
    fi
  fi
  pattern='^(WINGMAN_AUTH_(ISSUER|AUDIENCE|JWKS_URL|ALGORITHMS)=|# Auth0 \(I-014)'
  if [[ $dry -eq 1 ]]; then
    echo
    echo "== --dry-run: would write to $target (nothing changed) =="
    if [[ -f "$target" ]]; then
      echo "# would replace these existing lines:"
      grep -E "$pattern" "$target" || echo "# (none)"
    fi
    env_lines
  else
    tmp="$(mktemp "$target_dir/.env.auth0.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    if [[ -f "$target" ]]; then
      cp -p "$target" "$target.bak"
      grep -Ev "$pattern" "$target" >"$tmp" || true
      [[ -s "$tmp" && -n "$(tail -c1 "$tmp")" ]] && echo >>"$tmp"
    fi
    env_lines >>"$tmp"
    chmod 600 "$tmp"
    mv "$tmp" "$target"
    trap - EXIT
    echo
    note=""
    [[ -f "$target.bak" ]] && note=" (previous copy: $target.bak)"
    echo "Wrote the Auth0 lines to $target$note" >&2
  fi
fi

# ---------------------------------------------------------------- --check
if [[ $check -eq 1 ]]; then
  echo
  echo "== JWKS reachability: $jwks_url =="
  if ! command -v curl >/dev/null 2>&1; then
    echo "UNVERIFIED: curl is not installed; open $jwks_url in a browser instead." >&2
    exit 3
  fi
  if ! body="$(curl -fsS --max-time 15 --proto '=https' "$jwks_url" 2>&1)"; then
    echo "UNVERIFIED: could not fetch the JWKS ($(head -n1 <<<"$body"))." >&2
    echo "This is expected in a sandbox without outbound network; otherwise check the tenant domain." >&2
    exit 3
  fi
  if [[ "$body" != *'"keys"'* ]]; then
    echo "UNVERIFIED: $jwks_url answered, but not with a JWKS document (no \"keys\")." >&2
    exit 3
  fi
  if [[ "$body" != *'"RS256"'* && "$body" != *'"kty":"RSA"'* && "$body" != *'"kty": "RSA"'* ]]; then
    echo "WARNING: no RS256/RSA key listed; check the API's signing algorithm is RS256." >&2
    exit 3
  fi
  echo "OK: the JWKS lists signing keys (RS256)."
fi
