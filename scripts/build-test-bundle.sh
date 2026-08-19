#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:?test tag is required}"
OUTPUT_DIR="${2:-$PROJECT_ROOT/build/local-test/$TAG}"
MATRIX_DIR="$PROJECT_ROOT/app/build/outputs/apk/matrix/test-$TAG"

while IFS='=' read -r name value; do
    case "$name" in
        ZAPRET_VERSION_NAME|ZAPRET_VERSION_CODE|ZAPRET_PRERELEASE|ZAPRET_RELEASE_CHANNEL)
            export "$name=$value"
            ;;
        *) echo "Unexpected test version variable: $name" >&2; exit 1 ;;
    esac
done < <("$PROJECT_ROOT/scripts/derive-test-version.sh" "$TAG")
export ZAPRET_DEBUG_VERSION_NAME_SUFFIX=''

cd "$PROJECT_ROOT"
scripts/verify-project.sh
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
scripts/build-apk-matrix.sh debug "$MATRIX_DIR"
scripts/create-test-bundle.sh "$TAG" "$MATRIX_DIR" "$OUTPUT_DIR"
scripts/verify-test-bundle.sh "$TAG" "$OUTPUT_DIR"

echo "Local emulator-only test bundle is ready: $OUTPUT_DIR"
