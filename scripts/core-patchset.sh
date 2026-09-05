#!/usr/bin/env bash

# Sourced by core build scripts after core.properties. The upstream checkout stays
# pinned to CORE_COMMIT; the ordered application patchset is verified, applied only
# for compilation, recorded in artifacts, and reversed on every exit path.

: "${CORE_PATCH_FILE:?Missing CORE_PATCH_FILE}"
: "${CORE_PATCH_SHA256:?Missing CORE_PATCH_SHA256}"

verify_core_patchset() {
    local project_root="$1"
    printf '%s  %s\n' "$CORE_UDP_PATCH_SHA256" "$project_root/core-patches/sing-udp.json" | sha256sum -c - >/dev/null
    python3 - "$project_root/core-patches/sing-udp.json" <<'PY'
import hashlib, json, pathlib, sys
manifest = pathlib.Path(sys.argv[1])
data = json.loads(manifest.read_text())
for name, digest in [("sing_udp_buffer_test.go", data["test_sha256"])] + [
    (pin["patch"], pin["patch_sha256"]) for pin in data["versions"].values()
]:
    path = manifest.parent / name
    if path.parent != manifest.parent or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise SystemExit("UDP patchset hash mismatch: " + name)
PY
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

# A private verified copy avoids mutating Go's shared module cache. Save the
# patched go.mod before adding the local replace, restore it before reversing
# the source patchset. Production and symbol builds use the same cache path.
prepare_sing_udp_dependency() {
    local project_root="$1" source_dir="$2"
    SING_UDP_MOD_BACKUP="$(mktemp -d "${source_dir%/*}/sing-udp-mod.XXXXXX")"
    cp "$source_dir/go.mod" "$SING_UDP_MOD_BACKUP/go.mod"
    cp "$source_dir/go.sum" "$SING_UDP_MOD_BACKUP/go.sum"
    local dependency_root="$project_root/core-build/sing-udp-dependency"
    python3 "$project_root/scripts/prepare_sing_udp_patch.py" --source "$source_dir" --output "$dependency_root"
    local dependency
    dependency="$(head -n 1 "$dependency_root/module-path.txt")"
    (cd "$source_dir" && go mod edit "-replace=github.com/sagernet/sing=$dependency")
    cp "${dependency%/*}/provenance.json" "$project_root/core-build/output/sing-udp-provenance.json"
}

restore_sing_udp_dependency() {
    local source_dir="$1"
    if [[ -n "${SING_UDP_MOD_BACKUP:-}" ]]; then
        cp "$SING_UDP_MOD_BACKUP/go.mod" "$source_dir/go.mod"
        cp "$SING_UDP_MOD_BACKUP/go.sum" "$source_dir/go.sum"
        SING_UDP_MOD_BACKUP=
    fi
}
