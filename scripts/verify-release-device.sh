#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
PACKAGE="io.github.zapretkvn.android"
ACTIVITY="$PACKAGE/.MainActivity"
REPORT_DIR="${ZAPRET_RC_REPORT_DIR:-$PROJECT_ROOT/build/release-candidate}"
TEMP_DIR="$(mktemp -d -t zapret-kvn-release-device-XXXXXXXX)"

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
    rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT

for command in "$ADB" awk jq keytool sed sort; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
[[ -x "$APKSIGNER" ]]

# shellcheck source=lib/adb-select.sh
source "$PROJECT_ROOT/scripts/lib/adb-select.sh"
adb_select_device || exit 1

device_abi="$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
    arm64-v8a) release_abi=arm64-v8a ;;
    armeabi-v7a) release_abi=armeabi-v7a ;;
    x86_64) release_abi=x86_64 ;;
    *) echo "Unsupported release-candidate device ABI: $device_abi" >&2; exit 1 ;;
esac
device_sdk="$($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
device_model="$($ADB shell getprop ro.product.model | tr -d '\r')"

keytool -genkeypair -noprompt \
    -keystore "$TEMP_DIR/release-test.jks" -storepass changeit -keypass changeit \
    -alias release -keyalg RSA -keysize 3072 -validity 30 \
    -dname "CN=Zapret KVN release-device test" >/dev/null 2>&1
keytool -genkeypair -noprompt \
    -keystore "$TEMP_DIR/other.jks" -storepass changeit -keypass changeit \
    -alias other -keyalg RSA -keysize 3072 -validity 30 \
    -dname "CN=Zapret KVN incompatible test" >/dev/null 2>&1

build_release() {
    local version_name="$1"
    local version_code="$2"
    local output="$3"
    env \
        ZAPRET_SIGNING_STORE_FILE="$TEMP_DIR/release-test.jks" \
        ZAPRET_SIGNING_STORE_PASSWORD=changeit \
        ZAPRET_SIGNING_KEY_ALIAS=release \
        ZAPRET_SIGNING_KEY_PASSWORD=changeit \
        "$PROJECT_ROOT/gradlew" --no-configuration-cache :app:assembleRelease \
            -PzapretAbi="$release_abi" \
            -PzapretVersionName="$version_name" \
            -PzapretVersionCode="$version_code" >/dev/null
    mapfile -t apks < <(find "$PROJECT_ROOT/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*.apk' | sort)
    if [[ "${#apks[@]}" -ne 1 ]]; then
        echo "Expected one signed release APK, found ${#apks[@]}" >&2
        exit 1
    fi
    "$APKSIGNER" verify --verbose --print-certs "${apks[0]}" >/dev/null
    cp "${apks[0]}" "$output"
}

cd "$PROJECT_ROOT"
build_release 0.8.0-rc-device1 800001 "$TEMP_DIR/v1.apk"
build_release 0.8.0-rc-device2 800002 "$TEMP_DIR/v2.apk"
cert_v1="$($APKSIGNER verify --print-certs "$TEMP_DIR/v1.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
cert_v2="$($APKSIGNER verify --print-certs "$TEMP_DIR/v2.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ -n "$cert_v1" && "$cert_v1" == "$cert_v2" ]]

package_version() {
    "$ADB" shell dumpsys package "$PACKAGE" \
        | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' \
        | head -n 1 \
        | tr -d '\r'
}

"$ADB" uninstall "$PACKAGE" >/dev/null 2>&1 || true
"$ADB" logcat -c
"$ADB" install "$TEMP_DIR/v1.apk" >/dev/null
[[ "$(package_version)" == 800001 ]]
if "$ADB" shell run-as "$PACKAGE" id >/dev/null 2>&1; then
    echo "Release application is unexpectedly debuggable" >&2
    exit 1
fi

mkdir -p "$REPORT_DIR"
: > "$REPORT_DIR/cold-start.jsonl"
for run in 1 2 3 4 5; do
    launch="$($ADB shell am start -S -W -n "$ACTIVITY" | tr -d '\r')"
    total_time="$(sed -n 's/^TotalTime: //p' <<<"$launch" | tail -n 1)"
    wait_time="$(sed -n 's/^WaitTime: //p' <<<"$launch" | tail -n 1)"
    [[ "$total_time" =~ ^[0-9]+$ && "$wait_time" =~ ^[0-9]+$ ]]
    "$ADB" shell pidof "$PACKAGE" >/dev/null
    jq -cn \
        --argjson run "$run" \
        --argjson total_time_ms "$total_time" \
        --argjson wait_time_ms "$wait_time" \
        '{run:$run,total_time_ms:$total_time_ms,wait_time_ms:$wait_time_ms}' \
        >> "$REPORT_DIR/cold-start.jsonl"
done

mapfile -t times < <(jq -r '.total_time_ms' "$REPORT_DIR/cold-start.jsonl" | sort -n)
median_cold_start_ms="${times[2]}"
if (( median_cold_start_ms > 5000 )); then
    echo "Release cold-start median exceeds 5000 ms: $median_cold_start_ms" >&2
    exit 1
fi
if "$ADB" logcat -d -b crash | grep -Fq "$PACKAGE"; then
    echo "Release process crashed during cold-start gate" >&2
    "$ADB" logcat -d -b crash >&2
    exit 1
fi

first_install_time="$($ADB shell dumpsys package "$PACKAGE" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | head -n 1 | tr -d '\r')"
"$ADB" install -r "$TEMP_DIR/v2.apk" >/dev/null
[[ "$(package_version)" == 800002 ]]
[[ "$($ADB shell dumpsys package "$PACKAGE" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | head -n 1 | tr -d '\r')" == "$first_install_time" ]]
"$ADB" shell am start -S -W -n "$ACTIVITY" >/dev/null
"$ADB" shell pidof "$PACKAGE" >/dev/null

if downgrade_output="$($ADB install -r "$TEMP_DIR/v1.apk" 2>&1)"; then
    echo "Android unexpectedly accepted a release versionCode downgrade" >&2
    exit 1
fi
[[ "$downgrade_output" == *"VERSION_DOWNGRADE"* ]]

"$APKSIGNER" sign \
    --ks "$TEMP_DIR/other.jks" --ks-pass pass:changeit --key-pass pass:changeit \
    --out "$TEMP_DIR/other-signed.apk" "$TEMP_DIR/v2.apk"
if signature_output="$($ADB install -r "$TEMP_DIR/other-signed.apk" 2>&1)"; then
    echo "Android unexpectedly accepted an incompatible release signing key" >&2
    exit 1
fi
[[ "$signature_output" == *"UPDATE_INCOMPATIBLE"* ]]
[[ "$(package_version)" == 800002 ]]

jq -n \
    --arg device_model "$device_model" \
    --argjson api "$device_sdk" \
    --arg abi "$device_abi" \
    --argjson cold_start_runs 5 \
    --argjson median_cold_start_ms "$median_cold_start_ms" \
    --arg signing_identity "ephemeral-test-key" \
    '{device_model:$device_model,api:$api,abi:$abi,cold_start_runs:$cold_start_runs,median_cold_start_ms:$median_cold_start_ms,clean_install:true,same_key_upgrade:true,downgrade_rejected:true,incompatible_signature_rejected:true,release_debuggable:false,process_crash:false,signing_identity:$signing_identity}' \
    > "$REPORT_DIR/device-report.json"

echo "Release device gate passed: $REPORT_DIR/device-report.json"
