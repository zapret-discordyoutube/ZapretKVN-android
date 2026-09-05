#!/usr/bin/env bash
set -euo pipefail

# Fast offline freshness check: does app/libs/libbox.aar match the pinned core
# fingerprint (core.properties plus the verified core-patches manifest)?
#
# Exit codes:
#   0 - the AAR exists and matches every pin
#   2 - the AAR is missing or was built from different pins; scripts/build-core.sh fixes it
#   3 - core.properties and the patch manifest disagree; fix the pins first,
#       rebuilding the core will not help

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${1:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
: "${CORE_TAG:?Missing CORE_TAG}"
: "${CORE_COMMIT:?Missing CORE_COMMIT}"
: "${CORE_PATCH_FILE:?Missing CORE_PATCH_FILE}"
: "${CORE_PATCH_SHA256:?Missing CORE_PATCH_SHA256}"
: "${HYSTERIA_CORE_TAG:?Missing HYSTERIA_CORE_TAG}"
: "${HYSTERIA_CORE_COMMIT:?Missing HYSTERIA_CORE_COMMIT}"
: "${ANDROID_WIREGUARD_GO:?Missing ANDROID_WIREGUARD_GO}"
: "${ANDROID_AMNEZIAWG_GO:?Missing ANDROID_AMNEZIAWG_GO}"

# Run the patchset verification in a child shell: its internal set -e stays
# effective there, while a shell function called under `if !` would keep
# running past a failed hash comparison.
patchset_status=0
bash -c '
    set -euo pipefail
    # shellcheck disable=SC1090,SC1091
    source "$1/core.properties"
    # shellcheck disable=SC1091
    source "$2/core-patchset.sh"
    verify_core_patchset "$1"
' verify-core-patchset "$PROJECT_ROOT" "$SCRIPT_DIR" || patchset_status=$?
if (( patchset_status != 0 )); then
    echo "core.properties and $CORE_PATCH_FILE disagree; fix the pins before building" >&2
    exit 3
fi

LIBS_DIR="$PROJECT_ROOT/app/libs"
LIBBOX_AAR="$LIBS_DIR/libbox.aar"
LIBBOX_PROPERTIES="$LIBS_DIR/libbox.properties"

stale() {
    echo "libbox.aar is not built from the current pins: $1" >&2
    exit 2
}

[[ -s "$LIBBOX_AAR" ]] || stale "missing $LIBBOX_AAR"
[[ -f "$LIBBOX_PROPERTIES" ]] || stale "missing $LIBBOX_PROPERTIES"

recorded() {
    sed -n "s/^$1=//p" "$LIBBOX_PROPERTIES" | tail -n 1
}

for key in CORE_TAG CORE_COMMIT CORE_PATCH_FILE CORE_PATCH_SHA256 CORE_UDP_PATCH_SHA256 \
    HYSTERIA_CORE_TAG HYSTERIA_CORE_COMMIT XRAY_CORE_MODULE XRAY_CORE_COMMIT \
    ANDROID_WIREGUARD_GO ANDROID_AMNEZIAWG_GO; do
    expected="${!key}"
    actual="$(recorded "$key")"
    [[ "$actual" == "$expected" ]] ||
        stale "$key is '${actual:-<absent>}', pins require '$expected'"
done

RECORDED_SHA256="$(recorded LIBBOX_SHA256)"
[[ "$RECORDED_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
    stale "libbox.properties has no valid LIBBOX_SHA256"
ACTUAL_SHA256="$(sha256sum "$LIBBOX_AAR" | awk '{print $1}')"
[[ "$ACTUAL_SHA256" == "$RECORDED_SHA256" ]] ||
    stale "AAR sha256 $ACTUAL_SHA256 does not match recorded $RECORDED_SHA256"

echo "libbox.aar matches the pinned core fingerprint ($CORE_TAG, patches $CORE_PATCH_SHA256)."
