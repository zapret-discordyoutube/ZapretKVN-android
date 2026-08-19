#!/usr/bin/env bash

# Sourced by core build scripts after core.properties. The upstream checkout stays
# pinned to CORE_COMMIT; the ordered application patchset is verified, applied only
# for compilation, recorded in artifacts, and reversed on every exit path.

: "${CORE_PATCH_FILE:?Missing CORE_PATCH_FILE}"
: "${CORE_PATCH_SHA256:?Missing CORE_PATCH_SHA256}"

verify_core_patchset() {
    local project_root="$1"
    local manifest_path="$project_root/$CORE_PATCH_FILE"
    [[ -f "$manifest_path" ]] || {
        echo "Missing core patch manifest: $manifest_path" >&2
        return 1
    }
    printf '%s  %s\n' "$CORE_PATCH_SHA256" "$manifest_path" | sha256sum -c - >/dev/null

    local digest relative_path extra
    local count=0
    while read -r digest relative_path extra; do
        [[ -z "$extra" && "$digest" =~ ^[0-9a-f]{64}$ &&
            "$relative_path" =~ ^core-patches/[A-Za-z0-9._-]+\.patch$ ]] || {
            echo "Invalid core patch manifest entry: $digest $relative_path $extra" >&2
            return 1
        }
        [[ -f "$project_root/$relative_path" ]] || {
            echo "Missing core patch: $project_root/$relative_path" >&2
            return 1
        }
        printf '%s  %s\n' "$digest" "$project_root/$relative_path" | sha256sum -c - >/dev/null
        count=$((count + 1))
    done < "$manifest_path"
    (( count > 0 )) || {
        echo "Core patch manifest is empty: $manifest_path" >&2
        return 1
    }
}

core_patch_files() {
    local project_root="$1"
    local digest relative_path extra
    while read -r digest relative_path extra; do
        printf '%s\n' "$project_root/$relative_path"
    done < "$project_root/$CORE_PATCH_FILE"
}

apply_core_patchset() {
    local project_root="$1"
    local source_dir="$2"
    verify_core_patchset "$project_root"
    local patch_path
    while IFS= read -r patch_path; do
        git -C "$source_dir" apply --check "$patch_path"
        git -C "$source_dir" apply "$patch_path"
    done < <(core_patch_files "$project_root")
}

reverse_core_patchset() {
    local project_root="$1"
    local source_dir="$2"
    local -a patch_paths=()
    mapfile -t patch_paths < <(core_patch_files "$project_root")
    local index patch_path
    for ((index = ${#patch_paths[@]} - 1; index >= 0; index--)); do
        patch_path="${patch_paths[$index]}"
        git -C "$source_dir" apply --reverse --check "$patch_path"
        git -C "$source_dir" apply --reverse "$patch_path"
    done
}
