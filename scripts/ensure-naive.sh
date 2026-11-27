#!/usr/bin/env bash
set -euo pipefail

# Gradle preBuild hook: make sure core-build/naive/<abi>/libnaive.so holds the pinned STOCK NaiveProxy client
# (klzgrad/naiveproxy, owner decision 2026-11-27). libnaive.so is an ELF executable, not a JNI library: the app runs it
# from its native library directory (it is the only place Android 10+ lets an app exec from).
#
# Trust chain, all pinned in core.properties: the plugin APK of the release (SHA-256 == the one GitHub shows for the
# asset), its v2 signature (signer certificate SHA-256 == klzgrad's key), and the extracted binary (SHA-256). Any mismatch
# is fatal; a stale or unverifiable file is never kept.
#
#   scripts/ensure-naive.sh [PROJECT_ROOT]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${1:-$(cd "$SCRIPT_DIR/.." && pwd)}"
# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
: "${NAIVE_TAG:?Missing NAIVE_TAG}"
: "${NAIVE_REPOSITORY:?Missing NAIVE_REPOSITORY}"
: "${NAIVE_SIGNER_SHA256:?Missing NAIVE_SIGNER_SHA256}"

OUT="$PROJECT_ROOT/core-build/naive"
ABIS=(arm64-v8a armeabi-v7a x86_64)

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi
APKSIGNER="${ANDROID_HOME:-/opt/android-sdk}/build-tools/${ANDROID_BUILD_TOOLS:-36.0.0}/apksigner"

pin() { local name="NAIVE_${1}_SHA256_${2//-/_}"; name="${name^^}"; echo "${!name:?Missing $name}"; }

work=""
cleanup() { [[ -z "$work" ]] || rm -rf "$work"; }
trap cleanup EXIT

for abi in "${ABIS[@]}"; do
    target="$OUT/$abi/libnaive.so"
    want_lib="$(pin LIB "$abi")"
    if [[ -f "$target" ]] && [[ "$(sha256sum "$target" | cut -d' ' -f1)" == "$want_lib" ]]; then
        continue
    fi
    [[ -x "$APKSIGNER" ]] || { echo "apksigner not found: $APKSIGNER" >&2; exit 1; }
    [[ -n "$work" ]] || work="$(mktemp -d)"
    apk="$work/$abi.apk"
    url="https://github.com/$NAIVE_REPOSITORY/releases/download/$NAIVE_TAG/naiveproxy-plugin-$NAIVE_TAG-$abi.apk"
    echo "naive: downloading $abi plugin of $NAIVE_TAG" >&2
    curl -fsSL --retry 3 --max-time 300 -o "$apk" "$url"
    [[ "$(sha256sum "$apk" | cut -d' ' -f1)" == "$(pin APK "$abi")" ]] ||
        { echo "naive: $abi plugin APK SHA-256 does not match the pin" >&2; exit 1; }
    signer="$("$APKSIGNER" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
    [[ "$signer" == "$NAIVE_SIGNER_SHA256" ]] ||
        { echo "naive: $abi plugin APK is not signed by the pinned upstream key" >&2; exit 1; }
    unzip -p "$apk" "lib/$abi/libnaive.so" > "$work/libnaive.so"
    [[ "$(sha256sum "$work/libnaive.so" | cut -d' ' -f1)" == "$want_lib" ]] ||
        { echo "naive: $abi libnaive.so SHA-256 does not match the pin" >&2; exit 1; }
    mkdir -p "$OUT/$abi"
    install -m 0755 "$work/libnaive.so" "$target"
done
echo "naive $NAIVE_TAG: libnaive.so verified for ${ABIS[*]}" >&2
