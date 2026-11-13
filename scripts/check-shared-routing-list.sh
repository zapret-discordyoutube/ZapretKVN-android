#!/usr/bin/env bash
set -euo pipefail

# The list of domains that always go through the VPN (googlevideo.com and friends) is ONE canonical file kept
# byte-identical in both repositories:
#   android/shared/always-proxy-domains.json
#   windows/data/routing/always-proxy-domains.json
# Each repository's tests check that its own consumers (the Kotlin list, the Windows templates) match its copy;
# this script checks that the two copies match each other. Run by scripts/prepare-release.sh before a release.
#
# Usage: scripts/check-shared-routing-list.sh [ANDROID_FILE WINDOWS_FILE]   (defaults to the two repository paths)
# Exit codes: 0 identical; 1 different or missing; 2 usage. A missing Windows repository is reported, not ignored.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WINDOWS_ROOT="${ZAPRET_WINDOWS_ROOT:-$PROJECT_ROOT/../windows}"

if [[ $# -eq 0 ]]; then
    ANDROID_FILE="$PROJECT_ROOT/shared/always-proxy-domains.json"
    WINDOWS_FILE="$WINDOWS_ROOT/data/routing/always-proxy-domains.json"
elif [[ $# -eq 2 ]]; then
    ANDROID_FILE="$1"
    WINDOWS_FILE="$2"
else
    echo "Usage: $0 [ANDROID_FILE WINDOWS_FILE]" >&2
    exit 2
fi

for file in "$ANDROID_FILE" "$WINDOWS_FILE"; do
    if [[ ! -f "$file" ]]; then
        echo "check-shared-routing-list: missing $file" >&2
        exit 1
    fi
done

android_hash="$(sha256sum "$ANDROID_FILE" | cut -d' ' -f1)"
windows_hash="$(sha256sum "$WINDOWS_FILE" | cut -d' ' -f1)"
if [[ "$android_hash" != "$windows_hash" ]]; then
    echo "check-shared-routing-list: the two copies differ" >&2
    echo "  android: $android_hash  $ANDROID_FILE" >&2
    echo "  windows: $windows_hash  $WINDOWS_FILE" >&2
    echo "  Fix: copy the edited file over the other one, then update ForcedProxyDomains.suffixes and the four Windows templates (their tests say what is missing)." >&2
    exit 1
fi
echo "shared routing list: identical in both repositories ($android_hash)"
