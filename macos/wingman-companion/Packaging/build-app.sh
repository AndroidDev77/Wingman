#!/usr/bin/env bash
# Build Wingman Companion.app and, with a Developer ID, sign, notarize and staple a DMG.
# UNVERIFIED end to end: CI runs only the --adhoc path (bundle + ad-hoc signature +
# codesign --verify); nobody has notarized this app yet.
#
#   Packaging/build-app.sh --adhoc
#   Packaging/build-app.sh --identity "Developer ID Application: NAME (TEAMID)" \
#       --team TEAMID --profile path/to/DeveloperID.provisionprofile \
#       --notary-profile wingman-notary      # a `xcrun notarytool store-credentials` profile
#
# Output: .build/package/Wingman Companion.app and, when notarizing, WingmanCompanion.dmg.
set -euo pipefail
cd "$(dirname "$0")/.."

identity="" team="" profile="" notary="" adhoc=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --adhoc) adhoc=1; shift ;;
    --identity) identity="$2"; shift 2 ;;
    --team) team="$2"; shift 2 ;;
    --profile) profile="$2"; shift 2 ;;
    --notary-profile) notary="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
if [[ $adhoc -eq 0 && ( -z "$identity" || -z "$team" ) ]]; then
  echo "need --adhoc, or --identity and --team" >&2; exit 2
fi

swift build -c release --product WingmanCompanion
bin="$(swift build -c release --show-bin-path)/WingmanCompanion"

out=.build/package
app="$out/Wingman Companion.app"
rm -rf "$out" && mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$bin" "$app/Contents/MacOS/WingmanCompanion"
cp Packaging/Info.plist "$app/Contents/Info.plist"
plutil -lint "$app/Contents/Info.plist"

ent="$out/WingmanCompanion.entitlements"
if [[ $adhoc -eq 1 ]]; then
  # Ad-hoc: no team, so drop the keychain-group entitlements (Keychain calls will fail
  # with errSecMissingEntitlement; that build is for structure checks only).
  cp Packaging/WingmanCompanion.entitlements "$ent"
  /usr/libexec/PlistBuddy -c "Delete :com.apple.application-identifier" "$ent"
  /usr/libexec/PlistBuddy -c "Delete :keychain-access-groups" "$ent"
  plutil -lint "$ent"
  codesign --force --options runtime --entitlements "$ent" --sign - "$app"
else
  sed "s/TEAMID/$team/g" Packaging/WingmanCompanion.entitlements > "$ent"
  plutil -lint "$ent"
  if [[ -n "$profile" ]]; then cp "$profile" "$app/Contents/embedded.provisionprofile"; fi
  codesign --force --options runtime --timestamp --entitlements "$ent" --sign "$identity" "$app"
fi

codesign --verify --strict --verbose=2 "$app"
signed_ent="$(codesign -d --entitlements - --xml "$app" 2>/dev/null)"
grep -q "com.apple.security.automation.apple-events" <<<"$signed_ent"
if grep -q "com.apple.security.app-sandbox" <<<"$signed_ent"; then
  echo "the app must not be sandboxed" >&2; exit 1
fi
echo "built: $app"

if [[ $adhoc -eq 0 && -n "$notary" ]]; then
  dmg="$out/WingmanCompanion.dmg"
  hdiutil create -volname "Wingman Companion" -srcfolder "$app" -ov -format UDZO "$dmg"
  codesign --force --timestamp --sign "$identity" "$dmg"
  xcrun notarytool submit "$dmg" --keychain-profile "$notary" --wait
  xcrun stapler staple "$dmg"
  spctl --assess --type open --context context:primary-signature --verbose "$dmg"
  echo "notarized: $dmg"
fi
