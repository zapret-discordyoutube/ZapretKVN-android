#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:?test tag is required}"
BUNDLE_DIR="${2:?test bundle directory is required}"
ABIS=(arm64-v8a armeabi-v7a)

source "$PROJECT_ROOT/core.properties"
while IFS='=' read -r name value; do
    case "$name" in
        ZAPRET_VERSION_NAME|ZAPRET_VERSION_CODE|ZAPRET_PRERELEASE|ZAPRET_RELEASE_CHANNEL)
            printf -v "$name" '%s' "$value"
            ;;
        *) echo "Unexpected test version variable: $name" >&2; exit 1 ;;
    esac
done < <("$PROJECT_ROOT/scripts/derive-test-version.sh" "$TAG")

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
source "$PROJECT_ROOT/core.properties"
AAPT2="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
for command in jq sha256sum stat unzip; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
[[ -x "$AAPT2" && -x "$APKSIGNER" && -d "$BUNDLE_DIR" ]]

EXPECTED_FILES=(build-metadata.json)
for abi in "${ABIS[@]}"; do
    EXPECTED_FILES+=("Zapret-KVN-$TAG-$abi-debug.apk" "Zapret-KVN-$TAG-$abi-debug.apk.sha256")
done
mapfile -t EXPECTED_FILES < <(printf '%s\n' "${EXPECTED_FILES[@]}" | sort)
mapfile -t ACTUAL_FILES < <(
    find "$BUNDLE_DIR" -mindepth 1 -maxdepth 1 -type f ! -name RELEASE_NOTES.md -printf '%f\n' | sort
)
[[ "${ACTUAL_FILES[*]}" == "${EXPECTED_FILES[*]}" ]] || {
    echo "Test bundle must contain exactly five publishable assets" >&2
    printf 'Expected: %s\nActual: %s\n' "${EXPECTED_FILES[*]}" "${ACTUAL_FILES[*]}" >&2
    exit 1
}

METADATA="$BUNDLE_DIR/build-metadata.json"
jq -e \
    --arg version_name "$ZAPRET_VERSION_NAME" \
    --argjson version_code "$ZAPRET_VERSION_CODE" \
    --arg core_tag "$CORE_TAG" \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg source_commit "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" \
    '
      .schema == 1 and .channel == "test" and .emulator_only == true
      and .beta_updater_compatible == false
      and .application_id == "io.github.zapretkvn.android.debug"
      and .build_type == "debug"
      and .version_name == $version_name and .version_code == $version_code
      and .core_tag == $core_tag and .core_commit == $core_commit
      and .core_patch_sha256 == $core_patch_sha256
      and .source_commit == $source_commit
      and ([.artifacts[].abi] | sort) == ["arm64-v8a","armeabi-v7a"]
      and (.artifacts | length) == 2
    ' "$METADATA" >/dev/null

expected_signer=''
for abi in "${ABIS[@]}"; do
    apk_name="Zapret-KVN-$TAG-$abi-debug.apk"
    apk="$BUNDLE_DIR/$apk_name"
    digest="$(sha256sum "$apk" | awk '{print $1}')"
    size="$(stat -c '%s' "$apk")"
    read -r recorded_digest recorded_name extra < "$apk.sha256"
    [[ "$recorded_digest" == "$digest" && "$recorded_name" == "$apk_name" && -z "${extra:-}" ]]
    jq -e --arg abi "$abi" --arg file "$apk_name" --arg digest "$digest" --argjson size "$size" \
        '[.artifacts[] | select(.abi == $abi and .apk_file == $file and .apk_sha256 == $digest and .apk_size == $size)] | length == 1' \
        "$METADATA" >/dev/null
    mapfile -t native_abis < <(unzip -Z1 "$apk" | sed -n 's#^lib/\([^/]*\)/.*\.so$#\1#p' | sort -u)
    [[ "${#native_abis[@]}" -eq 1 && "${native_abis[0]}" == "$abi" ]]
    badging="$("$AAPT2" dump badging "$apk" | sed -n '1p')"
    [[ "$badging" == *"name='io.github.zapretkvn.android.debug'"* ]]
    [[ "$badging" == *"versionCode='$ZAPRET_VERSION_CODE'"* ]]
    [[ "$badging" == *"versionName='$ZAPRET_VERSION_NAME'"* ]]
    signer="$("$APKSIGNER" verify --print-certs "$apk" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
    [[ "$signer" =~ ^[0-9a-f]{64}$ ]]
    if [[ -z "$expected_signer" ]]; then expected_signer="$signer"; else [[ "$signer" == "$expected_signer" ]]; fi
done
jq -e --arg signer "$expected_signer" '.signer_sha256 == $signer' "$METADATA" >/dev/null

echo "Emulator-only test bundle verified: $TAG"
