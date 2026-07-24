#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-}"
BUNDLE_DIR="${2:-}"
RELEASE_REPOSITORY="${3:-${ZAPRET_UPDATE_REPOSITORY:-youtubediscord/ZapretKVN-android}}"
UPLOAD_TIMEOUT_SECONDS="${ZAPRET_RELEASE_UPLOAD_TIMEOUT_SECONDS:-120}"
UPLOAD_ATTEMPTS="${ZAPRET_RELEASE_UPLOAD_ATTEMPTS:-3}"

if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ || ! -d "$BUNDLE_DIR" ]]; then
    echo "Usage: $0 vMAJOR.MINOR.PATCH BUNDLE_DIRECTORY [OWNER/REPOSITORY]" >&2
    exit 1
fi
if [[ ! "$RELEASE_REPOSITORY" =~ ^[^/[:space:]]+/[^/[:space:]]+$ ]]; then
    echo "Invalid release repository: $RELEASE_REPOSITORY" >&2
    exit 1
fi
if [[ ! "$UPLOAD_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ||
    ! "$UPLOAD_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
    echo "Upload timeout and attempt count must be positive integers" >&2
    exit 1
fi
for command in gh jq sha256sum stat timeout; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done

BUNDLE_DIR="$(cd "$BUNDLE_DIR" && pwd)"
NOTES_FILE="$BUNDLE_DIR/RELEASE_NOTES.md"
VERSION_NAME="${TAG#v}"
EXPECTED_NAMES=(
    "Zapret-KVN-$TAG-arm64-v8a.apk"
    "Zapret-KVN-$TAG-arm64-v8a.apk.sha256"
    "Zapret-KVN-$TAG-armeabi-v7a.apk"
    "Zapret-KVN-$TAG-armeabi-v7a.apk.sha256"
    "Zapret-KVN-$TAG-x86_64.apk"
    "Zapret-KVN-$TAG-x86_64.apk.sha256"
    "release-metadata-v2.json"
    "release-metadata.json"
)
mapfile -t EXPECTED_NAMES < <(printf '%s\n' "${EXPECTED_NAMES[@]}" | sort)

[[ -f "$NOTES_FILE" ]] || {
    echo "Missing release notes: $NOTES_FILE" >&2
    exit 1
}
for name in "${EXPECTED_NAMES[@]}"; do
    [[ -f "$BUNDLE_DIR/$name" ]] || {
        echo "Missing stable release asset: $name" >&2
        exit 1
    }
done

release_json() {
    gh api "repos/$RELEASE_REPOSITORY/releases/tags/$TAG"
}

verify_release_identity() {
    local json="$1"
    jq -e \
        --arg tag "$TAG" \
        '
            .tag_name == $tag
            and .draft == true
            and .prerelease == false
        ' <<<"$json" >/dev/null || {
        echo "Existing GitHub Release is not the resumable stable draft for $TAG" >&2
        exit 1
    }
}

remote_asset_count() {
    local json="$1"
    local name="$2"
    jq -r --arg name "$name" '[.assets[] | select(.name == $name)] | length' <<<"$json"
}

remote_asset_matches() {
    local json="$1"
    local file="$2"
    local name size digest
    name="$(basename "$file")"
    size="$(stat -c '%s' "$file")"
    digest="sha256:$(sha256sum "$file" | awk '{print $1}')"
    jq -e \
        --arg name "$name" \
        --argjson size "$size" \
        --arg digest "$digest" \
        '
            [.assets[] | select(.name == $name)] as $matches
            | ($matches | length) == 1
            and $matches[0].state == "uploaded"
            and $matches[0].size == $size
            and $matches[0].digest == $digest
        ' <<<"$json" >/dev/null
}

assert_remote_asset_safe() {
    local json="$1"
    local file="$2"
    local name count
    name="$(basename "$file")"
    count="$(remote_asset_count "$json" "$name")"
    if [[ "$count" -eq 0 ]]; then
        return 1
    fi
    if [[ "$count" -ne 1 ]] || ! remote_asset_matches "$json" "$file"; then
        echo "Remote asset exists but does not match the local SHA-256 digest: $name" >&2
        echo "Refusing to delete or replace it; publish a correction under a new tag." >&2
        exit 1
    fi
}

upload_one_asset() {
    local file="$1"
    local name attempt status json
    name="$(basename "$file")"
    for ((attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++)); do
        echo "Uploading $name (attempt $attempt/$UPLOAD_ATTEMPTS)"
        status=0
        timeout --foreground "${UPLOAD_TIMEOUT_SECONDS}s" \
            gh release upload "$TAG" "$file" --repo "$RELEASE_REPOSITORY" || status=$?

        if ! json="$(release_json)"; then
            echo "Could not read draft state after uploading $name" >&2
            exit 1
        fi
        verify_release_identity "$json"
        if remote_asset_matches "$json" "$file"; then
            echo "Verified remote SHA-256 digest: $name"
            return 0
        fi
        if [[ "$(remote_asset_count "$json" "$name")" -ne 0 ]]; then
            assert_remote_asset_safe "$json" "$file"
        fi
        if (( attempt == UPLOAD_ATTEMPTS )); then
            echo "Upload did not produce a verified remote asset: $name (status $status)" >&2
            return 1
        fi
        echo "Upload attempt failed without creating an asset: $name (status $status)" >&2
    done
}

if json="$(release_json 2>/dev/null)"; then
    verify_release_identity "$json"
    echo "Resuming existing stable draft: $TAG"
else
    gh release create "$TAG" \
        --repo "$RELEASE_REPOSITORY" \
        --verify-tag \
        --draft \
        --title "Zapret KVN $VERSION_NAME" \
        --notes-file "$NOTES_FILE"
    json="$(release_json)"
    verify_release_identity "$json"
    echo "Created stable draft: $TAG"
fi

for name in "${EXPECTED_NAMES[@]}"; do
    file="$BUNDLE_DIR/$name"
    json="$(release_json)"
    verify_release_identity "$json"
    if assert_remote_asset_safe "$json" "$file"; then
        echo "Reusing verified remote asset: $name"
    else
        upload_one_asset "$file"
    fi
done

json="$(release_json)"
verify_release_identity "$json"
mapfile -t REMOTE_NAMES < <(jq -r '.assets[].name' <<<"$json" | sort)
if [[ "${REMOTE_NAMES[*]}" != "${EXPECTED_NAMES[*]}" ]]; then
    echo "Draft asset set differs from the required eight stable files" >&2
    printf 'Expected: %s\nActual: %s\n' "${EXPECTED_NAMES[*]}" "${REMOTE_NAMES[*]}" >&2
    exit 1
fi
for name in "${EXPECTED_NAMES[@]}"; do
    assert_remote_asset_safe "$json" "$BUNDLE_DIR/$name"
done

gh release edit "$TAG" \
    --repo "$RELEASE_REPOSITORY" \
    --draft=false \
    --prerelease=false \
    --latest \
    --title "Zapret KVN $VERSION_NAME" \
    --notes-file "$NOTES_FILE"

json="$(release_json)"
jq -e \
    --arg tag "$TAG" \
    '
        .tag_name == $tag
        and .draft == false
        and .prerelease == false
    ' <<<"$json" >/dev/null
mapfile -t REMOTE_NAMES < <(jq -r '.assets[].name' <<<"$json" | sort)
[[ "${REMOTE_NAMES[*]}" == "${EXPECTED_NAMES[*]}" ]]
for name in "${EXPECTED_NAMES[@]}"; do
    assert_remote_asset_safe "$json" "$BUNDLE_DIR/$name"
done

echo "Published immutable stable release: $(jq -r .html_url <<<"$json")"
