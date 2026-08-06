#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi

: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
BUILD_TOOLS_DIR="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS"
AAPT2="$BUILD_TOOLS_DIR/aapt2"

if [[ ! -x "$AAPT2" ]]; then
    echo "Missing pinned aapt2: $AAPT2" >&2
    exit 1
fi

cd "$PROJECT_ROOT"
scripts/verify-project.sh
if [[ "${ZAPRET_CORE_CACHE_HIT:-false}" == true ]]; then
    scripts/verify-core-cache.sh
else
    scripts/build-core.sh
    scripts/build-native-symbols.sh
fi
scripts/verify-rule-sets.sh
(
    cd core-build/output
    sha256sum -c SHA256SUMS
)
GRADLE_ARGS=(--stacktrace)
if [[ -n "${ZAPRET_SIGNING_STORE_FILE:-}${ZAPRET_SIGNING_STORE_PASSWORD:-}${ZAPRET_SIGNING_KEY_ALIAS:-}${ZAPRET_SIGNING_KEY_PASSWORD:-}" ]]; then
    # Signing credentials are Gradle inputs and must never be serialized into a reusable cache.
    GRADLE_ARGS+=(--no-configuration-cache)
fi
./gradlew "${GRADLE_ARGS[@]}" \
    testDebugUnitTest \
    lintDebug \
    assembleDebug \
    assembleDebugAndroidTest
scripts/build-apk-matrix.sh release

RELEASE_MATRIX_DIR="app/build/outputs/apk/matrix/release"
RELEASE_ABIS=(arm64-v8a armeabi-v7a x86_64)
mapfile -t RELEASE_APKS < <(find "$RELEASE_MATRIX_DIR" -maxdepth 1 -type f -name '*.apk' | sort)
if [[ "${#RELEASE_APKS[@]}" -ne "${#RELEASE_ABIS[@]}" ]]; then
    echo "Expected ${#RELEASE_ABIS[@]} release APKs, found ${#RELEASE_APKS[@]}" >&2
    exit 1
fi
RELEASE_APK="$RELEASE_MATRIX_DIR/app-arm64-v8a-release.apk"
[[ -f "$RELEASE_APK" ]]

mkdir -p app/build
"$AAPT2" dump permissions "$RELEASE_APK" > app/build/permissions.txt
if grep -Eq 'WAKE_LOCK|REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' app/build/permissions.txt; then
    echo "Release APK contains a forbidden battery permission" >&2
    exit 1
fi
if ! grep -Fq 'android.permission.REQUEST_INSTALL_PACKAGES' app/build/permissions.txt; then
    echo "Release APK is missing the manual updater installer permission" >&2
    exit 1
fi

"$AAPT2" dump xmltree "$RELEASE_APK" --file AndroidManifest.xml > app/build/manifest-tree.txt
if grep -Fq 'android:process' app/build/manifest-tree.txt; then
    echo "Release APK declares a secondary Android process" >&2
    exit 1
fi
if grep -Eq 'Gate6ProcessProbeReceiver|Gate7UpgradeProbeReceiver|Gate8StressProbeReceiver|Gate8PerformanceProbeReceiver' app/build/manifest-tree.txt; then
    echo "Release APK contains a debug-only gate receiver" >&2
    exit 1
fi
PROFILEABLE_CONTEXT="$(grep -F -A1 'E: profileable' app/build/manifest-tree.txt || true)"
if [[ "$(grep -Fc 'E: profileable' app/build/manifest-tree.txt || true)" -ne 1 ]] ||
    ! grep -Fq 'android:shell' <<<"$PROFILEABLE_CONTEXT" ||
    ! grep -Fq '=true' <<<"$PROFILEABLE_CONTEXT"; then
    echo "Release APK must remain shell-profileable for the physical performance gate" >&2
    exit 1
fi

VPN_SERVICE_COUNT="$(grep -Fc 'android.permission.BIND_VPN_SERVICE' app/build/manifest-tree.txt || true)"
if (( VPN_SERVICE_COUNT != 1 )); then
    echo "Release APK must declare exactly one VPN service; found $VPN_SERVICE_COUNT" >&2
    exit 1
fi

ALWAYS_ON_COUNT="$(grep -Fc 'android.net.VpnService.SUPPORTS_ALWAYS_ON' app/build/manifest-tree.txt || true)"
ALWAYS_ON_CONTEXT="$(grep -F -A1 'android.net.VpnService.SUPPORTS_ALWAYS_ON' app/build/manifest-tree.txt || true)"
if (( ALWAYS_ON_COUNT != 1 )) || ! grep -Fq '=false' <<<"$ALWAYS_ON_CONTEXT"; then
    echo "Release APK must explicitly opt its VPN service out of Always-on" >&2
    exit 1
fi

"$AAPT2" dump resources "$RELEASE_APK" > app/build/resources.txt
for legal_resource in raw/license_gpl_3 raw/notice raw/sing_box_extended_license raw/sing_geoip_license raw/third_party_notices xml/diagnostic_file_paths; do
    if ! grep -Fq "$legal_resource" app/build/resources.txt; then
        echo "Release APK is missing legal resource: $legal_resource" >&2
        exit 1
    fi
done

for abi in "${RELEASE_ABIS[@]}"; do
    scripts/verify-release-candidate.sh \
        "$RELEASE_MATRIX_DIR/app-$abi-release.apk" \
        app/build/outputs/mapping/release/mapping.txt \
        "$abi"
done

(
    cd app/build/outputs/apk
    find . -type f -name '*.apk' -print0 \
        | sort -z \
        | xargs -0 sha256sum > ../../APK-SHA256SUMS
)

echo "Build, tests and release-candidate security verification passed."
