#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
# shellcheck disable=SC1091
source "$PROJECT_ROOT/scripts/core-patchset.sh"

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"

SOURCE_DIR="$PROJECT_ROOT/core-build/source"
OUTPUT_DIR="$PROJECT_ROOT/core-build/output"
PRODUCTION_AAR="$OUTPUT_DIR/libbox.aar"
TEMP_DIR="$(mktemp -d -t zapret-kvn-symbols-XXXXXXXX)"
SYMBOL_DIR="$TEMP_DIR/native-debug-symbols"
SYMBOL_ZIP="$OUTPUT_DIR/native-debug-symbols.zip"
GOPATH_DIR="$PROJECT_ROOT/core-build/gopath"
LLVM_STRIP="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

CORE_PATCH_APPLIED=false
cleanup() {
    if [[ "$CORE_PATCH_APPLIED" == true ]]; then
        reverse_core_patchset "$PROJECT_ROOT" "$SOURCE_DIR"
        CORE_PATCH_APPLIED=false
    fi
    rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT

for command in git go java jq python3 touch unzip zip readelf sha256sum; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
[[ -x "$LLVM_STRIP" && -f "$PRODUCTION_AAR" && -d "$SOURCE_DIR/.git" ]]
[[ "$(git -C "$SOURCE_DIR" rev-parse HEAD)" == "$CORE_COMMIT" ]]
[[ -z "$(git -C "$SOURCE_DIR" status --porcelain)" ]]
apply_core_patchset "$PROJECT_ROOT" "$SOURCE_DIR"
CORE_PATCH_APPLIED=true

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
export GOPATH="$GOPATH_DIR"
export PATH="$GOPATH/bin:$PATH"
export GOTOOLCHAIN=local
export GOFLAGS=

LIBBOX_TAGS="with_gvisor,with_quic,with_wireguard,with_masque,with_mtproxy,with_trusttunnel,with_call,with_openvpn,with_sudoku,with_snell,with_utls,with_naive_outbound,with_clash_api,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird"

(
    cd "$SOURCE_DIR"
    "$GOPATH/bin/gomobile" bind \
        -o "$TEMP_DIR/libbox-native-symbols.aar" \
        -target android/arm64 \
        -androidapi 23 \
        -javapkg=io.nekohasekai \
        -libname=box \
        -trimpath \
        -buildvcs=false \
        -ldflags "-X github.com/sagernet/sing-box/constant.Version=${CORE_TAG#v} -X internal/godebug.defaultGODEBUG=multipathtcp=0 -buildid= -checklinkname=0" \
        -tags "$LIBBOX_TAGS" \
        ./experimental/libbox
)

mkdir -p "$TEMP_DIR/symbols/arm64-v8a"
unzip -p "$TEMP_DIR/libbox-native-symbols.aar" jni/arm64-v8a/libbox.so \
    > "$TEMP_DIR/symbols/arm64-v8a/libbox.so"
unzip -p "$PRODUCTION_AAR" jni/arm64-v8a/libbox.so > "$TEMP_DIR/libbox-production.so"
cp "$TEMP_DIR/symbols/arm64-v8a/libbox.so" "$TEMP_DIR/libbox-stripped.so"
"$LLVM_STRIP" -s "$TEMP_DIR/libbox-stripped.so"

# -s/-w changes only non-allocated ELF sections and header offsets. Every loadable section must
# remain byte-identical, at the same virtual address, before this file is accepted for symbols.
python3 - \
    "$TEMP_DIR/libbox-production.so" \
    "$TEMP_DIR/symbols/arm64-v8a/libbox.so" \
    "$TEMP_DIR/libbox-stripped.so" <<'PY'
from pathlib import Path
import hashlib
import struct
import sys

def allocated_sections(path):
    data = Path(path).read_bytes()
    if data[:6] != b"\x7fELF\x02\x01":
        raise SystemExit(f"Expected 64-bit little-endian ELF: {path}")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data, 0)
    section_offset, entry_size, count, names_index = header[6], header[11], header[12], header[13]
    sections = [
        struct.unpack_from("<IIQQQQIIQQ", data, section_offset + index * entry_size)
        for index in range(count)
    ]
    names_header = sections[names_index]
    names = data[names_header[4]:names_header[4] + names_header[5]]
    result = {}
    for section in sections:
        end = names.find(b"\0", section[0])
        name = names[section[0]:end].decode("utf-8")
        section_type, flags, address, offset, size = section[1], section[2], section[3], section[4], section[5]
        if flags & 0x2 and section_type != 8:
            digest = hashlib.sha256(data[offset:offset + size]).hexdigest()
            result[name] = (address, size, digest)
    return result

production = allocated_sections(sys.argv[1])
symbols = allocated_sections(sys.argv[2])
stripped = allocated_sections(sys.argv[3])
for label, candidate in (("unstripped", symbols), ("locally stripped", stripped)):
    if production != candidate:
        missing = sorted(set(production) ^ set(candidate))
        changed = sorted(name for name in set(production) & set(candidate) if production[name] != candidate[name])
        raise SystemExit(
            f"{label} symbols do not match production loadable sections; "
            f"missing={missing}, changed={changed}"
        )
print(f"Native symbols match {len(production)} production loadable ELF sections.")
PY

readelf -S "$TEMP_DIR/symbols/arm64-v8a/libbox.so" | grep -Fq '.symtab'
readelf -S "$TEMP_DIR/symbols/arm64-v8a/libbox.so" | grep -Fq '.debug_info'

mkdir -p "$SYMBOL_DIR/arm64-v8a"
install -m 0644 "$TEMP_DIR/symbols/arm64-v8a/libbox.so" "$SYMBOL_DIR/arm64-v8a/libbox.so"
TZ=UTC touch -t 198001010000 "$SYMBOL_DIR/arm64-v8a/libbox.so"
rm -f -- "$SYMBOL_ZIP"
(
    cd "$SYMBOL_DIR"
    zip -9 -X -q "$SYMBOL_ZIP" arm64-v8a/libbox.so
)

PRODUCTION_SHA256="$(sha256sum "$TEMP_DIR/libbox-production.so" | awk '{print $1}')"
SYMBOL_SHA256="$(sha256sum "$SYMBOL_DIR/arm64-v8a/libbox.so" | awk '{print $1}')"
ZIP_SHA256="$(sha256sum "$SYMBOL_ZIP" | awk '{print $1}')"
jq -n \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg abi "arm64-v8a" \
    --arg production_sha256 "$PRODUCTION_SHA256" \
    --arg symbol_sha256 "$SYMBOL_SHA256" \
    --arg archive_sha256 "$ZIP_SHA256" \
    '{core_commit:$core_commit,core_patch_sha256:$core_patch_sha256,abi:$abi,production_libbox_sha256:$production_sha256,unstripped_libbox_sha256:$symbol_sha256,archive_sha256:$archive_sha256,loadable_sections_exact:true}' \
    > "$OUTPUT_DIR/native-symbols-metadata.json"
(
    cd "$OUTPUT_DIR"
    sha256sum native-debug-symbols.zip native-symbols-metadata.json > native-symbols-SHA256SUMS
)

echo "Exact arm64 libbox native symbols written to $SYMBOL_ZIP"
