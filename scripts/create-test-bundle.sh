#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:?test tag is required}"
MATRIX_DIR="${2:?debug APK matrix directory is required}"
OUTPUT_DIR="${3:?output directory is required}"
TESTED_APIS="${ZAPRET_TESTED_EMULATOR_APIS:-unknown}"
TEST_STATUS="${ZAPRET_TEST_STATUS:-compiled}"
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

if [[ "$ZAPRET_PRERELEASE" != true || "$ZAPRET_RELEASE_CHANNEL" != test ]]; then
    echo "Only test prerelease bundles are accepted" >&2
    exit 1
fi
for command in jq realpath sha256sum stat; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
[[ -d "$MATRIX_DIR" ]]
safe_output_root="$(realpath -m "$PROJECT_ROOT/build/local-test")"
resolved_output="$(realpath -m "$OUTPUT_DIR")"
[[ "$resolved_output" == "$safe_output_root/"* ]] || {
    echo "Test bundle output must be below $safe_output_root" >&2
    exit 1
}
if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
[[ -x "$APKSIGNER" ]]

mkdir -p "$OUTPUT_DIR"
find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -type f -delete
artifacts='[]'
for abi in "${ABIS[@]}"; do
    source_apk="$MATRIX_DIR/app-$abi-debug.apk"
    apk_name="Zapret-KVN-$TAG-$abi-debug.apk"
    target_apk="$OUTPUT_DIR/$apk_name"
    [[ -f "$source_apk" ]] || { echo "Missing debug APK: $source_apk" >&2; exit 1; }
    cp "$source_apk" "$target_apk"
    digest="$(sha256sum "$target_apk" | awk '{print $1}')"
    size="$(stat -c '%s' "$target_apk")"
    printf '%s  %s\n' "$digest" "$apk_name" > "$target_apk.sha256"
    artifacts="$(jq -cn \
        --argjson prior "$artifacts" \
        --arg abi "$abi" \
        --arg apk_file "$apk_name" \
        --arg apk_sha256 "$digest" \
        --argjson apk_size "$size" \
        '$prior + [{abi:$abi,apk_file:$apk_file,apk_sha256:$apk_sha256,apk_size:$apk_size}]')"
done
first_apk="$OUTPUT_DIR/Zapret-KVN-$TAG-arm64-v8a-debug.apk"
signer_sha256="$("$APKSIGNER" verify --print-certs "$first_apk" \
    | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' \
    | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
[[ "$signer_sha256" =~ ^[0-9a-f]{64}$ ]]

jq -n \
    --arg version_name "$ZAPRET_VERSION_NAME" \
    --argjson version_code "$ZAPRET_VERSION_CODE" \
    --arg source_commit "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" \
    --arg core_tag "$CORE_TAG" \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg hysteria_core_tag "$HYSTERIA_CORE_TAG" \
    --arg hysteria_core_commit "$HYSTERIA_CORE_COMMIT" \
    --arg tested_apis "$TESTED_APIS" \
    --arg test_status "$TEST_STATUS" \
    --arg signer_sha256 "$signer_sha256" \
    --argjson artifacts "$artifacts" \
    '{
        schema:1,
        channel:"test",
        emulator_only:true,
        beta_updater_compatible:false,
        application_id:"io.github.zapretkvn.android.debug",
        build_type:"debug",
        version_name:$version_name,
        version_code:$version_code,
        source_commit:$source_commit,
        core_tag:$core_tag,
        hysteria_core_tag:$hysteria_core_tag,
        hysteria_core_commit:$hysteria_core_commit,
        core_commit:$core_commit,
        core_patch_sha256:$core_patch_sha256,
        tested_emulator_apis:$tested_apis,
        test_status:$test_status,
        signer_sha256:$signer_sha256,
        artifacts:$artifacts
    }' > "$OUTPUT_DIR/build-metadata.json"

cat > "$OUTPUT_DIR/RELEASE_NOTES.md" <<EOF
# Zapret KVN $ZAPRET_VERSION_NAME

host/emulator-verified; physical Android device not verified.
Hysteria2 URI, TLS and diagnostics emulator-only test build.

- Embeds official Hysteria core/extras app/v2.12.2 for plain, Salamander and Gecko.
- Preserves the exact imported URI and uses a secret-safe transport fingerprint for subscription identity.
- Preserves certificate SHA-256 pinning, ECH, port hopping and Android-protected sockets; native JSON also supports custom CA and mTLS.
- Adds diagnostic schema v6 with attempt/stage/profile/outbound/protocol context and opaque endpoints.
- Uses synthetic test credentials only; Gecko is not confirmed on a physical Android device.

- Not a Stable or Beta-updater release.
- Not for F-Droid or Telegram distribution.
- Tested emulator APIs: $TESTED_APIS.
- Automated test status: $TEST_STATUS.
- Physical Pixel/device verification remains open.
EOF

echo "Test bundle created: $OUTPUT_DIR"
