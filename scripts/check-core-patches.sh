#!/usr/bin/env bash
set -euo pipefail

# Strict check of the core patch series: every patch hash matches the manifest, the manifest hash matches
# core.properties, and ALL patches apply, in manifest order and without fuzz, to a clean copy of the pinned core.
#
# Why it exists: a patch generated in a tree that was built before an earlier patch was regenerated looks fine
# locally and then fails to apply in the real series (it happened with patch 0012 on 2026-11-08). Run this after
# every change to core-patches/ and before committing.
#
#   scripts/check-core-patches.sh
#
# Needs the pinned core checkout (core-build/source, or ZAPRET_CORE_SOURCE). Exit codes: 0 ok, 1 failure,
# 3 skipped because the checkout is absent (so CI without the core tree is not blocked).

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$PROJECT_ROOT/core.properties"
: "${CORE_COMMIT:?Missing CORE_COMMIT}"
: "${CORE_PATCH_SHA256:?Missing CORE_PATCH_SHA256}"
MANIFEST="$PROJECT_ROOT/core-patches/series.sha256"
SOURCE="${ZAPRET_CORE_SOURCE:-$PROJECT_ROOT/core-build/source}"

printf '%s  %s\n' "$CORE_PATCH_SHA256" "$MANIFEST" | sha256sum -c - >/dev/null || {
    echo "series.sha256 does not match CORE_PATCH_SHA256 in core.properties" >&2
    exit 1
}
if [[ ! -d "$SOURCE/.git" ]]; then
    echo "skipped: no pinned core checkout at $SOURCE (set ZAPRET_CORE_SOURCE)" >&2
    exit 3
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -a "$SOURCE" "$WORK/src"
git -C "$WORK/src" checkout -q --detach "$CORE_COMMIT"

count=0
while read -r digest relative_path extra; do
    [[ -z "$extra" && "$digest" =~ ^[0-9a-f]{64}$ && "$relative_path" =~ ^core-patches/[A-Za-z0-9._-]+\.patch$ ]] || {
        echo "Invalid manifest entry: $digest $relative_path $extra" >&2
        exit 1
    }
    printf '%s  %s\n' "$digest" "$PROJECT_ROOT/$relative_path" | sha256sum -c - >/dev/null || {
        echo "Hash mismatch: $relative_path" >&2
        exit 1
    }
    git -C "$WORK/src" apply --whitespace=nowarn "$PROJECT_ROOT/$relative_path" 2>/dev/null || {
        echo "Does not apply cleanly in manifest order: $relative_path" >&2
        exit 1
    }
    count=$((count + 1))
done < "$MANIFEST"

echo "ok: $count patches apply in manifest order to $CORE_COMMIT"
