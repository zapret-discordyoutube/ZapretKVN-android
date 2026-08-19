#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_TYPE="${1:-release}"
OUTPUT_DIR="${2:-$PROJECT_ROOT/app/build/outputs/apk/matrix/$BUILD_TYPE}"
SUPPORTED_ABIS=(arm64-v8a armeabi-v7a x86_64)

case "$BUILD_TYPE" in
    debug) GRADLE_TASK=:app:assembleDebug ;;
    release) GRADLE_TASK=:app:assembleRelease ;;
    *) echo "Build type must be debug or release: $BUILD_TYPE" >&2; exit 1 ;;
esac

for command in find unzip; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done

SOURCE_DIR="$PROJECT_ROOT/app/build/outputs/apk/$BUILD_TYPE"
mkdir -p "$OUTPUT_DIR" "$SOURCE_DIR"
find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.apk' -delete

GRADLE_ARGS=(--stacktrace)
if [[ -v ZAPRET_DEBUG_VERSION_NAME_SUFFIX ]]; then
    GRADLE_ARGS+=("-PzapretDebugVersionNameSuffix=$ZAPRET_DEBUG_VERSION_NAME_SUFFIX")
fi
if [[ -n "${ZAPRET_SIGNING_STORE_FILE:-}${ZAPRET_SIGNING_STORE_PASSWORD:-}${ZAPRET_SIGNING_KEY_ALIAS:-}${ZAPRET_SIGNING_KEY_PASSWORD:-}" ]]; then
    # Signing credentials are Gradle inputs and must never enter a reusable configuration cache.
    GRADLE_ARGS+=(--no-configuration-cache)
fi

cd "$PROJECT_ROOT"
for abi in "${SUPPORTED_ABIS[@]}"; do
    find "$SOURCE_DIR" -type f -name '*.apk' -delete
    ./gradlew "${GRADLE_ARGS[@]}" "$GRADLE_TASK" -PzapretAbi="$abi"

    mapfile -t apks < <(find "$SOURCE_DIR" -type f -name '*.apk' | sort)
    if [[ "${#apks[@]}" -ne 1 ]]; then
        echo "Expected one $BUILD_TYPE APK for $abi, found ${#apks[@]}" >&2
        exit 1
    fi

    mapfile -t native_abis < <(
        unzip -Z1 "${apks[0]}" \
            | sed -n 's#^lib/\([^/]*\)/.*\.so$#\1#p' \
            | sort -u
    )
    if [[ "${#native_abis[@]}" -ne 1 || "${native_abis[0]}" != "$abi" ]]; then
        echo "Unexpected native ABI set for $abi: ${native_abis[*]:-none}" >&2
        exit 1
    fi
    if ! unzip -Z1 "${apks[0]}" | grep -Fx "lib/$abi/libbox.so" >/dev/null; then
        echo "$BUILD_TYPE APK does not contain lib/$abi/libbox.so" >&2
        exit 1
    fi

    destination="$OUTPUT_DIR/app-$abi-$BUILD_TYPE.apk"
    cp "${apks[0]}" "$destination"
    echo "Built $destination ($(stat -c '%s' "$destination") bytes)"
done

# Keep distributable outputs only in the explicit matrix directory. This avoids
# accidentally uploading the last architecture twice under Gradle's generic name.
find "$SOURCE_DIR" -type f -name '*.apk' -delete

echo "$BUILD_TYPE APK matrix written to $OUTPUT_DIR"
