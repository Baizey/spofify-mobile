#!/usr/bin/env bash
# Prove both variant identities and exercise the signing path with disposable keys, never network/ADB.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
umask 077
export MOBILE_KEYSTORE_PASSWORD=disposable-ci-only
export MOBILE_KEY_PASSWORD=$MOBILE_KEYSTORE_PASSWORD
export MOBILE_KEY_ALIAS=fixture

make_fixture() {
  local label=$1
  keytool -genkeypair -keystore "$work/$label.p12" -storetype PKCS12 -storepass:env MOBILE_KEYSTORE_PASSWORD \
    -alias "$MOBILE_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 1 -dname "CN=Disposable Mobile $label Test" >/dev/null
  keytool -exportcert -keystore "$work/$label.p12" -storepass:env MOBILE_KEYSTORE_PASSWORD \
    -alias "$MOBILE_KEY_ALIAS" | sha256sum | cut -d ' ' -f 1 > "$work/$label.sha256"
}

release=(python3 scripts/mobile_release.py)
args=(--version-code 12345 --version-name 0.0.0-ci)
"${release[@]}" verify-debug app/build/outputs/apk/debug/app-debug.apk
"${release[@]}" stage-unsigned "${args[@]}" app/build/outputs/apk/release "$work/unsigned.apk"
make_fixture first
make_fixture second
export MOBILE_KEYSTORE_B64
MOBILE_KEYSTORE_B64=$(base64 -w0 "$work/first.p12")
"${release[@]}" sign "${args[@]}" --cert-file "$work/first.sha256" --unsigned "$work/unsigned.apk" \
  "$work/spofify-mobile-release.apk"
unset MOBILE_KEYSTORE_B64 MOBILE_KEYSTORE_PASSWORD MOBILE_KEY_PASSWORD MOBILE_KEY_ALIAS
"${release[@]}" verify-apk "${args[@]}" --cert-file "$work/first.sha256" "$work/spofify-mobile-release.apk"
if "${release[@]}" verify-apk "${args[@]}" --cert-file "$work/second.sha256" "$work/spofify-mobile-release.apk" \
    > "$work/wrong-signer.log" 2>&1; then
  echo 'Unexpectedly accepted a certificate from the wrong key.' >&2
  exit 1
fi
grep -F 'APK signer does not match the public certificate anchor.' "$work/wrong-signer.log"
if "${release[@]}" verify-apk --version-code 12346 --version-name 0.0.0-ci --cert-file "$work/first.sha256" \
    "$work/spofify-mobile-release.apk" > "$work/wrong-version.log" 2>&1; then
  echo 'Unexpectedly accepted an APK with the wrong version code.' >&2
  exit 1
fi
grep -F 'APK versionCode does not match requested code.' "$work/wrong-version.log"
echo 'Verified both APK identities, disposable release signing, wrong signer rejection, and wrong version rejection.'
