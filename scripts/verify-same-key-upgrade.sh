#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
PACKAGE="io.github.zapretkvn.android.debug"
COMPONENT="$PACKAGE/io.github.zapretkvn.android.debug.Gate7UpgradeProbeReceiver"
TEMP_DIR="$(mktemp -d -t zapret-kvn-upgrade-XXXXXXXX)"

# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"

cleanup() {
    "$ADB" uninstall "$PACKAGE" >/dev/null 2>&1 || true
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

# shellcheck source=lib/adb-select.sh
source "$PROJECT_ROOT/scripts/lib/adb-select.sh"
adb_select_device || exit 1

cd "$PROJECT_ROOT"
./gradlew :app:assembleDebug -PzapretVersionName=0.7.1-test1 -PzapretVersionCode=701001 >/dev/null
cp app/build/outputs/apk/debug/app-debug.apk "$TEMP_DIR/v1.apk"
./gradlew :app:assembleDebug -PzapretVersionName=0.7.1-test2 -PzapretVersionCode=701002 >/dev/null
cp app/build/outputs/apk/debug/app-debug.apk "$TEMP_DIR/v2.apk"

probe() {
    local action="$1"
    "$ADB" shell am broadcast --receiver-foreground -a "$action" -n "$COMPONENT" \
        | sed -n 's/.*data="\([^"]*\)".*/\1/p' | tail -n 1
}

assert_field() {
    local status="$1"
    local expected="$2"
    if [[ ";$status;" != *";$expected;"* ]]; then
        echo "Missing '$expected' in upgrade probe: $status" >&2
        exit 1
    fi
}

"$ADB" uninstall "$PACKAGE" >/dev/null 2>&1 || true
"$ADB" install "$TEMP_DIR/v1.apk" >/dev/null
"$ADB" shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
seed="$(probe io.github.zapretkvn.android.debug.GATE7_SEED)"
assert_field "$seed" "version=701001"
assert_field "$seed" "profiles=1"
assert_field "$seed" "active=true"

"$ADB" install -r "$TEMP_DIR/v2.apk" >/dev/null
"$ADB" shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
upgraded="$(probe io.github.zapretkvn.android.debug.GATE7_STATUS)"
for field in version=701002 profiles=1 active=true theme=Dark channel=Beta apps=1; do
    assert_field "$upgraded" "$field"
done

if downgrade_output="$($ADB install -r "$TEMP_DIR/v1.apk" 2>&1)"; then
    echo "Android unexpectedly accepted a versionCode downgrade" >&2
    exit 1
fi
if [[ "$downgrade_output" != *"VERSION_DOWNGRADE"* ]]; then
    echo "Unexpected downgrade result: $downgrade_output" >&2
    exit 1
fi

keytool -genkeypair -noprompt \
    -keystore "$TEMP_DIR/other.jks" -storepass changeit -keypass changeit \
    -alias other -keyalg RSA -keysize 2048 -validity 30 \
    -dname "CN=Zapret KVN incompatible test" >/dev/null 2>&1
"$APKSIGNER" sign \
    --ks "$TEMP_DIR/other.jks" --ks-pass pass:changeit --key-pass pass:changeit \
    --out "$TEMP_DIR/other-signed.apk" "$TEMP_DIR/v2.apk"
if signature_output="$($ADB install -r "$TEMP_DIR/other-signed.apk" 2>&1)"; then
    echo "Android unexpectedly accepted an incompatible signing key" >&2
    exit 1
fi
if [[ "$signature_output" != *"UPDATE_INCOMPATIBLE"* ]]; then
    echo "Unexpected signature result: $signature_output" >&2
    exit 1
fi

final="$(probe io.github.zapretkvn.android.debug.GATE7_STATUS)"
for field in version=701002 profiles=1 active=true theme=Dark channel=Beta apps=1; do
    assert_field "$final" "$field"
done

echo "Gate 7 same-key upgrade, persistence, downgrade and signature rejection passed: $final"
