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
cmp -s "$TMP_DIR/zapret-ru-domains.srs" "$ASSETS/zapret-ru-domains.srs" || {
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
expect_no_match "$ASSETS/zapret-ru-domains.srs" "example.com"
expect_match "$ASSETS/zapret-ru-ip.srs" "5.255.255.5"
expect_match "$ASSETS/zapret-ru-ip.srs" "2a02:6b8::feed:0ff"
expect_no_match "$ASSETS/zapret-ru-ip.srs" "1.1.1.1"
expect_no_match "$ASSETS/zapret-ru-ip.srs" "2606:4700:4700::1111"

echo "Packaged rule-set hashes, reproducibility and RU/non-RU IPv4/IPv6 matches passed."
