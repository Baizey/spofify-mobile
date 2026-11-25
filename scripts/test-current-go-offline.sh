#!/usr/bin/env bash
# Paired Android/Go test; no provider or production network access.
set -euo pipefail
revision=d17804f0d5c033cb6737e3fdbbc5cada5dd06dad
repo=${1:?Usage: bash scripts/test-current-go-offline.sh /path/to/spofify-go}
root=$(mktemp -d "${TMPDIR:-/tmp}/spofify-android-go.XXXXXXXX")
trap 'rm -rf -- "$root"' EXIT
mkdir "$root/src"
git -C "$repo" archive "$revision" | tar -x -C "$root/src"
(cd "$root/src" && GOPROXY=off GOSUMDB=off go build -trimpath -ldflags "-X main.sourceRevision=$revision" -o "$root/fixture" ./cmd/offline-fixture)
cd -- "$(dirname -- "$0")/.."
SPOFIFY_GO_FIXTURE_BIN="$root/fixture" ./gradlew --offline --no-daemon :app:testDebugUnitTest --tests '*AccountPersonalGoFixtureTest'
