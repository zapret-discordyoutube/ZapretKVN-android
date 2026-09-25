#!/usr/bin/env bash
set -euo pipefail

# Keeps only the newest local release/test bundles. Published assets live in
# Forgejo, so older local copies are disposable; the current tag is never removed.
ARCHIVE_DIR="${1:?archive directory is required}"
CURRENT_TAG="${2:?current tag is required}"
KEEP="${ZAPRET_LOCAL_ARCHIVE_KEEP:-3}"

[[ "$KEEP" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid ZAPRET_LOCAL_ARCHIVE_KEEP: $KEEP" >&2; exit 1; }
[[ -d "$ARCHIVE_DIR" ]] || exit 0

mapfile -t tags < <(
    find "$ARCHIVE_DIR" -mindepth 1 -maxdepth 1 -type d -name 'v[0-9]*' -printf '%f\n' | sort -rV
)
for tag in "${tags[@]:KEEP}"; do
    [[ "$tag" == "$CURRENT_TAG" ]] && continue
    rm -rf -- "${ARCHIVE_DIR:?}/$tag"
    echo "Pruned local archive: $ARCHIVE_DIR/$tag"
done
