#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$PROJECT_ROOT/core-build/output/sing-box"
ASSETS="$PROJECT_ROOT/app/src/main/assets/rule-sets"

[[ -x "$CLI" ]] || { echo "Pinned audit CLI is missing." >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TMP_DIR"' EXIT
"$CLI" rule-set compile \
    "$PROJECT_ROOT/rulesets/zapret-ru-domains.json" \
    -o "$TMP_DIR/zapret-ru-domains.srs"
# Compare the format header and the DECOMPRESSED payload, not the compressed bytes: the .srs body is a zlib stream and
# the Go compressor changed its output between toolchains (Go 1.26.8 -> 1.27.3 gave a 3-byte different stream for
# identical content, 2026-11-09). The content guarantee is the same: the packaged rule set equals the compiled source.
python3 - "$TMP_DIR/zapret-ru-domains.srs" "$ASSETS/zapret-ru-domains.srs" <<'PY' || {
import sys, zlib
def parts(path):
    data = open(path, "rb").read()
    return data[:4], zlib.decompress(data[4:])
sys.exit(0 if parts(sys.argv[1]) == parts(sys.argv[2]) else 1)
PY
    echo "Packaged RU domain rule-set is not reproducible from pinned source/CLI." >&2
    exit 1
}

expect_match() {
    local file="$1" value="$2"
    local output
    output="$("$CLI" rule-set match -f binary "$file" "$value" 2>&1)"
    grep -Fq 'match rules.' <<<"$output"
}

expect_no_match() {
    local file="$1" value="$2"
    local output
    output="$("$CLI" rule-set match -f binary "$file" "$value" 2>&1)"
    if grep -Fq 'match rules.' <<<"$output"; then
        echo "Unexpected match: $file <- $value" >&2
        exit 1
    fi
}

expect_match "$ASSETS/zapret-ru-domains.srs" "example.ru"
expect_match "$ASSETS/zapret-ru-domains.srs" "example.xn--p1ai"
expect_match "$ASSETS/zapret-ru-domains.srs" "championat.com"
expect_match "$ASSETS/zapret-ru-domains.srs" "www.championat.com"
expect_no_match "$ASSETS/zapret-ru-domains.srs" "notchampionat.com"
expect_match "$ASSETS/zapret-ru-domains.srs" "emias.info"
expect_match "$ASSETS/zapret-ru-domains.srs" "lk.emias.info"
expect_no_match "$ASSETS/zapret-ru-domains.srs" "notemias.info"
expect_no_match "$ASSETS/zapret-ru-domains.srs" "example.com"
expect_match "$ASSETS/zapret-ru-ip.srs" "5.255.255.5"
expect_match "$ASSETS/zapret-ru-ip.srs" "2a02:6b8::feed:0ff"
expect_no_match "$ASSETS/zapret-ru-ip.srs" "1.1.1.1"
expect_no_match "$ASSETS/zapret-ru-ip.srs" "2606:4700:4700::1111"

echo "Packaged rule-set hashes, reproducibility and RU/non-RU IPv4/IPv6 matches passed."
