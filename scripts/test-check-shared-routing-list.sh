#!/usr/bin/env bash
set -euo pipefail

# Offline test of scripts/check-shared-routing-list.sh on throwaway files.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
CHECK="$SCRIPT_DIR/check-shared-routing-list.sh"

fail() { echo "FAIL: $1" >&2; exit 1; }

printf '{"suffixes":["a.example"]}\n' > "$WORK_DIR/a.json"
cp "$WORK_DIR/a.json" "$WORK_DIR/b.json"
"$CHECK" "$WORK_DIR/a.json" "$WORK_DIR/b.json" >/dev/null || fail "identical copies must pass"
echo "ok: identical copies pass"

printf '{"suffixes":["a.example","b.example"]}\n' > "$WORK_DIR/b.json"
if msg="$("$CHECK" "$WORK_DIR/a.json" "$WORK_DIR/b.json" 2>&1)"; then fail "different copies must fail"; fi
[[ "$msg" == *"the two copies differ"* ]] || fail "unclear message: $msg"
echo "ok: different copies fail with a clear message"

if msg="$("$CHECK" "$WORK_DIR/a.json" "$WORK_DIR/missing.json" 2>&1)"; then fail "a missing copy must fail"; fi
[[ "$msg" == *"missing"* ]] || fail "unclear message for a missing file: $msg"
echo "ok: a missing copy fails"

rc=0; "$CHECK" one-argument-only >/dev/null 2>&1 || rc=$?
[[ "$rc" == 2 ]] || fail "wrong argument count must exit 2, got $rc"
echo "ok: usage error exits 2"

"$CHECK" >/dev/null 2>&1 || [[ ! -d "$SCRIPT_DIR/../../windows" ]] || fail "the two real repository copies must be identical"
echo "test-check-shared-routing-list: all passed"
