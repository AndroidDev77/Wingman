#!/usr/bin/env bash
# WING-026: pre-release gate. Runs the repository's own checks in order and stops at the
# first failure, so nothing is built or handed to testers from a red tree.
#
#   scripts/release/check.sh [--dry-run] [--paid]
#
#   1. scripts/backend-check.sh       ruff, format, mypy, pytest (real Postgres)
#   2. contracts/validate.sh          OpenAPI, JSON Schemas, examples
#   3. scripts/migration-check.sh     alembic up/down/up on an empty database, no drift
#   4. scripts/e2e/run.sh             E2E flows F1-F9, failure injection X1-X5, zero duplicate sends
#   5. scripts/e2e/platforms/run.sh   WING-032 cross-platform mock E2E (SMS, Tinder/Bumble/Hinge
#                                     notifications, Hinge experimental, iMessage companion,
#                                     discovery), zero duplicate sends, gate-vs-backend cross-check
#
# Before any of them (also with --dry-run): scripts/release/check_feature_gates.py. It fails when
# config/release/feature_gates.json marks a feature autonomous without a VERIFIED, PASSED ON
# DEVICE evidence record for every capability it needs (WING-032, docs/integrations/release_gates.md).
#
# Not run here (needs KVM and an emulator): the CI job "E2E Android emulator"; and the
# physical-device SMS checklist (I-003), which only a human can run. Check both in CI / in
# docs/integrations/android_sms.md before a release (docs/beta/release_checklist.md).
#
# --paid  refuse (exit 3) unless gate G5 in docs/STATUS.md is no longer marked blocked:
#         no paid launch without one reliable real message connector verified on a
#         physical device. The private beta itself never needs --paid.
# --dry-run  print the plan and the G5 state; run nothing.
#
# Exit: 0 all passed; 1 a check failed (later checks not run); 2 usage; 3 paid launch refused;
#       4 feature gates violated (nothing else run).
# Env: WINGMAN_RELEASE_REPO overrides the repository root (tests).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="${WINGMAN_RELEASE_REPO:-$(cd "$here/../.." && pwd)}"

dry=0 paid=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    --paid) paid=1 ;;
    -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "check.sh: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

checks=(
  "scripts/backend-check.sh"
  "contracts/validate.sh"
  "scripts/migration-check.sh"
  "scripts/e2e/run.sh"
  "scripts/e2e/platforms/run.sh"
)

# WING-032 release feature gates: nothing may be labeled autonomous without device evidence.
echo "release feature gates (config/release/feature_gates.json):"
if ! python3 "$here/check_feature_gates.py" --repo "$repo"; then
  echo "RELEASE REFUSED: feature gates claim more than the evidence shows (docs/integrations/release_gates.md)." >&2
  exit 4
fi

# G5 = "one real connector verified on a physical device". Read from the status dashboard:
# the row is blocked while it carries the ⛔ marker (or the word "blocked").
g5_row="$(grep -E '^\| *G5 *\|' "$repo/docs/STATUS.md" 2>/dev/null | head -n1 || true)"
if [[ -z "$g5_row" ]]; then
  g5="unknown"
elif grep -qiE '⛔|blocked' <<<"$g5_row"; then
  g5="blocked"
else
  g5="met"
fi
echo "gate G5 (real connector verified on a physical device): $g5"

if [[ $paid -eq 1 && "$g5" != "met" ]]; then
  echo "PAID LAUNCH REFUSED: G5 is $g5. No paid launch without one reliable real message connector" >&2
  echo "verified on a physical device (docs/beta/release_checklist.md). A limited private beta is" >&2
  echo "still possible without --paid." >&2
  exit 3
fi

if [[ $dry -eq 1 ]]; then
  for c in "${checks[@]}"; do echo "would run: $c"; done
  echo "dry run: nothing executed"
  exit 0
fi

n=0
for c in "${checks[@]}"; do
  n=$((n + 1))
  printf '\n==> [%d/%d] %s\n' "$n" "${#checks[@]}" "$c"
  if [[ ! -x "$repo/$c" ]]; then
    echo "PRE-RELEASE CHECK FAILED: $c is missing or not executable" >&2
    exit 1
  fi
  if ! (cd "$repo" && "./$c"); then
    echo "PRE-RELEASE CHECK FAILED at [$n/${#checks[@]}] $c; later checks not run. Do not release." >&2
    exit 1
  fi
done

printf '\nPRE-RELEASE CHECKS PASSED (%d/%d)\n' "${#checks[@]}" "${#checks[@]}"
if [[ "$g5" != "met" ]]; then
  echo "Release scope: LIMITED PRIVATE BETA only (G5 $g5: SMS send UNVERIFIED, no paid launch)."
fi
echo "Still required by hand: CI 'E2E Android emulator' green on this commit; docs/beta/release_checklist.md."
