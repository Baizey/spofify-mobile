#!/usr/bin/env bash
set -euo pipefail

# Linux tools and SHA-256 from https://developer.android.com/studio#command-line-tools-only.
revision=15859902
checksum=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
: "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}"

sdk="$RUNNER_TEMP/spofify-android-sdk"
staging=$(mktemp -d "$RUNNER_TEMP/spofify-android-tools.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
archive="$staging/tools.zip"
curl --fail --silent --show-error --retry 2 --max-time 240 \
    "https://dl.google.com/android/repository/commandlinetools-linux-${revision}_latest.zip" \
    --output "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check --status
mkdir -p "$sdk/cmdline-tools"
unzip -q "$archive" -d "$staging"
mv -T -- "$staging/cmdline-tools" "$sdk/cmdline-tools/latest"
manager="$sdk/cmdline-tools/latest/bin/sdkmanager"

# Accept the SDK licenses non-interactively. Only the exit status of sdkmanager
# matters here: yes normally gets SIGPIPE when sdkmanager exits.
(set +o pipefail; yes | "$manager" --sdk_root="$sdk" --licenses >/dev/null)
"$manager" --sdk_root="$sdk" --install "$@"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$sdk" "$sdk" >> "$GITHUB_ENV"
