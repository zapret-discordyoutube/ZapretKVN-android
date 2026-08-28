#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"

OUTPUT_DIR="$PROJECT_ROOT/core-build/output"
LIBS_DIR="$PROJECT_ROOT/app/libs"
LIBBOX_AAR="$LIBS_DIR/libbox.aar"
LIBBOX_PROPERTIES="$LIBS_DIR/libbox.properties"

for command in cmp jq sha256sum strings unzip; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done

required_files=(
    "$LIBBOX_AAR"
    "$LIBBOX_PROPERTIES"
    "$OUTPUT_DIR/SHA256SUMS"
    "$OUTPUT_DIR/core-build-metadata.json"
    "$OUTPUT_DIR/libbox.aar"
    "$OUTPUT_DIR/native-debug-symbols.zip"
    "$OUTPUT_DIR/native-symbols-SHA256SUMS"
    "$OUTPUT_DIR/native-symbols-metadata.json"
    "$OUTPUT_DIR/sing-box"
)
for path in "${required_files[@]}"; do
    if [[ ! -f "$path" ]]; then
        echo "Cached core artifact is missing: $path" >&2
        exit 1
    fi
done

(
    cd "$OUTPUT_DIR"
    sha256sum --strict -c SHA256SUMS >/dev/null
    sha256sum --strict -c native-symbols-SHA256SUMS >/dev/null
)

cmp -s "$LIBBOX_AAR" "$OUTPUT_DIR/libbox.aar" || {
    echo "Cached app and output libbox AAR files differ" >&2
    exit 1
}

grep -Fqx "CORE_TAG=$CORE_TAG" "$LIBBOX_PROPERTIES"
grep -Fqx "CORE_COMMIT=$CORE_COMMIT" "$LIBBOX_PROPERTIES"
grep -Fqx "CORE_PATCH_FILE=$CORE_PATCH_FILE" "$LIBBOX_PROPERTIES"
grep -Fqx "CORE_PATCH_SHA256=$CORE_PATCH_SHA256" "$LIBBOX_PROPERTIES"
grep -Fqx "HYSTERIA_CORE_TAG=$HYSTERIA_CORE_TAG" "$LIBBOX_PROPERTIES"
grep -Fqx "HYSTERIA_CORE_COMMIT=$HYSTERIA_CORE_COMMIT" "$LIBBOX_PROPERTIES"
source "$PROJECT_ROOT/scripts/core-patchset.sh"
verify_core_patchset "$PROJECT_ROOT"
EXPECTED_LIBBOX_SHA256="$(sed -n 's/^LIBBOX_SHA256=//p' "$LIBBOX_PROPERTIES")"
if [[ ! "$EXPECTED_LIBBOX_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
    [[ "$EXPECTED_LIBBOX_SHA256" != "$(sha256sum "$LIBBOX_AAR" | awk '{print $1}')" ]]; then
    echo "Cached libbox AAR does not match libbox.properties" >&2
    exit 1
fi

jq -e \
    --arg repository "$CORE_REPOSITORY" \
    --arg tag "$CORE_TAG" \
    --arg commit "$CORE_COMMIT" \
    --arg patch_file "$CORE_PATCH_FILE" \
    --arg patch_sha256 "$CORE_PATCH_SHA256" \
    --arg hysteria_core_tag "$HYSTERIA_CORE_TAG" \
    --arg hysteria_core_commit "$HYSTERIA_CORE_COMMIT" \
    --arg go "$GO_VERSION" \
    --arg gomobile "$GOMOBILE_VERSION" \
    --arg ndk "$ANDROID_NDK_VERSION" \
    '.repository == $repository and
     .tag == $tag and
     .commit == $commit and
     .patch_file == $patch_file and
     .patch_sha256 == $patch_sha256 and
     .hysteria_core_tag == $hysteria_core_tag and
     .hysteria_core_commit == $hysteria_core_commit and
     .go == $go and
     .gomobile == $gomobile and
     .android_ndk == $ndk and
     .abis == ["arm64-v8a", "armeabi-v7a", "x86_64"]' \
    "$OUTPUT_DIR/core-build-metadata.json" >/dev/null

unzip -tq "$OUTPUT_DIR/libbox.aar" >/dev/null
for abi in arm64-v8a armeabi-v7a x86_64; do
    marker="$(
        unzip -p "$OUTPUT_DIR/libbox.aar" "jni/$abi/libbox.so" \
            | strings \
            | grep -F 'DisableSomeRoamingForBrokenMobileSemantics' \
            || true
    )"
    if [[ -z "$marker" ]]; then
            echo "Android WireGuard roaming patch marker is missing for $abi" >&2
            exit 1
    fi
done
mapfile -t LIBBOX_NATIVE_ENTRIES < <(
    unzip -Z1 "$OUTPUT_DIR/libbox.aar" \
        | grep -E '^jni/[^/]+/libbox\.so$' \
        | sort
)
EXPECTED_LIBBOX_NATIVE_ENTRIES=(
    "jni/arm64-v8a/libbox.so"
    "jni/armeabi-v7a/libbox.so"
    "jni/x86_64/libbox.so"
)
if [[ "${LIBBOX_NATIVE_ENTRIES[*]}" != "${EXPECTED_LIBBOX_NATIVE_ENTRIES[*]}" ]]; then
    echo "Unexpected cached libbox ABI set: ${LIBBOX_NATIVE_ENTRIES[*]:-none}" >&2
    exit 1
fi

unzip -tq "$OUTPUT_DIR/native-debug-symbols.zip" >/dev/null
if [[ "$(unzip -Z1 "$OUTPUT_DIR/native-debug-symbols.zip")" != "arm64-v8a/libbox.so" ]]; then
    echo "Unexpected cached native-symbol archive contents" >&2
    exit 1
fi

PRODUCTION_NATIVE_SHA256="$(
    unzip -p "$OUTPUT_DIR/libbox.aar" jni/arm64-v8a/libbox.so | sha256sum | awk '{print $1}'
)"
SYMBOL_NATIVE_SHA256="$(
    unzip -p "$OUTPUT_DIR/native-debug-symbols.zip" arm64-v8a/libbox.so | sha256sum | awk '{print $1}'
)"
SYMBOL_ARCHIVE_SHA256="$(sha256sum "$OUTPUT_DIR/native-debug-symbols.zip" | awk '{print $1}')"
jq -e \
    --arg commit "$CORE_COMMIT" \
    --arg patch_sha256 "$CORE_PATCH_SHA256" \
    --arg production "$PRODUCTION_NATIVE_SHA256" \
    --arg symbols "$SYMBOL_NATIVE_SHA256" \
    --arg archive "$SYMBOL_ARCHIVE_SHA256" \
    '.core_commit == $commit and
     .core_patch_sha256 == $patch_sha256 and
     .abi == "arm64-v8a" and
     .production_libbox_sha256 == $production and
     .unstripped_libbox_sha256 == $symbols and
     .archive_sha256 == $archive and
     .loadable_sections_exact == true' \
    "$OUTPUT_DIR/native-symbols-metadata.json" >/dev/null

"$PROJECT_ROOT/scripts/verify-core-version.sh" "$OUTPUT_DIR/sing-box"
"$PROJECT_ROOT/scripts/verify-fixtures.sh" "$OUTPUT_DIR/sing-box"

echo "Cached native core artifacts passed integrity and provenance checks."
