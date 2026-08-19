#!/usr/bin/env bash
set -euo pipefail

tag="${1:-}"
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)-test\.([0-9]+)$ ]]; then
    echo "Test tag must be vMAJOR.MINOR.PATCH-test.N" >&2
    exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
test_number="${BASH_REMATCH[4]}"
if (( major > 20 || minor > 999 || patch > 999 || test_number < 1 || test_number > 98 )); then
    echo "Test version is outside the deterministic versionCode range" >&2
    exit 1
fi

version_code=$((10#$major * 100000000 + 10#$minor * 100000 + 10#$patch * 100 + 10#$test_number))
if (( version_code <= 0 || version_code > 2100000000 )); then
    echo "Derived Android versionCode is invalid: $version_code" >&2
    exit 1
fi

echo "ZAPRET_VERSION_NAME=${tag#v}"
echo "ZAPRET_VERSION_CODE=$version_code"
echo "ZAPRET_PRERELEASE=true"
echo "ZAPRET_RELEASE_CHANNEL=test"
