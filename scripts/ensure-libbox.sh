#!/usr/bin/env bash
set -euo pipefail

# Gradle preBuild hook: verify that app/libs/libbox.aar matches the pinned core
# fingerprint and rebuild the core automatically when it is stale and the pinned
# toolchain is available. Set ZAPRET_CORE_AUTOBUILD=0 to fail instead of
# rebuilding. Any non-zero exit means the app build must not proceed with the
# current AAR; a silent fallback to a stale core is forbidden.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${1:-$(cd "$SCRIPT_DIR/.." && pwd)}"
CHECK_SCRIPT="$PROJECT_ROOT/scripts/check-libbox-fingerprint.sh"

status=0
"$CHECK_SCRIPT" "$PROJECT_ROOT" || status=$?
if [[ "$status" -eq 0 ]]; then
    exit 0
fi
if [[ "$status" -ne 2 ]]; then
    exit "$status"
fi

if [[ "${ZAPRET_CORE_AUTOBUILD:-1}" == "0" ]]; then
    echo "Automatic core rebuild is disabled (ZAPRET_CORE_AUTOBUILD=0); run scripts/build-core.sh" >&2
    exit 2
fi

# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
: "${GO_VERSION:?Missing GO_VERSION}"
: "${ANDROID_NDK_VERSION:?Missing ANDROID_NDK_VERSION}"

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi

blockers=()
for required_command in git go java javac sha256sum unzip; do
    command -v "$required_command" >/dev/null ||
        blockers+=("missing command: $required_command")
done
if command -v go >/dev/null; then
    GO_ACTUAL="$(go env GOVERSION)"
    [[ "$GO_ACTUAL" == "go$GO_VERSION" ]] ||
        blockers+=("Go $GO_VERSION required, found $GO_ACTUAL")
fi
if command -v java >/dev/null; then
    JAVA_ACTUAL="$(java -version 2>&1 | head -n 1)"
    [[ "$JAVA_ACTUAL" == *"17."* ]] ||
        blockers+=("OpenJDK 17 required, found: $JAVA_ACTUAL")
fi
if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
    blockers+=("ANDROID_HOME must point to an installed Android SDK")
elif [[ ! -f "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION/source.properties" ]]; then
    blockers+=("missing pinned Android NDK: $ANDROID_HOME/ndk/$ANDROID_NDK_VERSION")
fi

if (( ${#blockers[@]} > 0 )); then
    {
        echo "libbox.aar was built from different pins and cannot be rebuilt automatically:"
        printf ' - %s\n' "${blockers[@]}"
        echo "Prepare the pinned toolchain and run scripts/build-core.sh."
    } >&2
    exit 2
fi

echo "libbox.aar is stale; rebuilding the pinned core with scripts/build-core.sh"
"$PROJECT_ROOT/scripts/build-core.sh"
"$CHECK_SCRIPT" "$PROJECT_ROOT"
