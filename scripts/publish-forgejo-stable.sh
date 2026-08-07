#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-}"
BUNDLE_DIR="${2:-}"
RELEASE_REPOSITORY="${3:-${ZAPRET_UPDATE_REPOSITORY:-zapretdiscordyoutube/ZapretKVN-android}}"
FORGEJO_BASE_URL="${ZAPRET_FORGEJO_URL:-https://git.zapret.moe}"
FORGEJO_API_URL="$FORGEJO_BASE_URL/api/v1"
TOKEN_FILE="${ZAPRET_FORGEJO_TOKEN_FILE:-${HOME:?HOME is required}/.config/forgejo/zapret-kvn-android-release-token}"
UPLOAD_TIMEOUT_SECONDS="${ZAPRET_RELEASE_UPLOAD_TIMEOUT_SECONDS:-180}"
UPLOAD_ATTEMPTS="${ZAPRET_RELEASE_UPLOAD_ATTEMPTS:-3}"

if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ || ! -d "$BUNDLE_DIR" ]]; then
    echo "Usage: $0 vMAJOR.MINOR.PATCH BUNDLE_DIRECTORY [OWNER/REPOSITORY]" >&2
    exit 1
fi
if [[ ! "$RELEASE_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "Invalid release repository: $RELEASE_REPOSITORY" >&2
    exit 1
fi
if [[ ! "$FORGEJO_BASE_URL" =~ ^https://[^/]+$ ]]; then
    echo "Forgejo base URL must be one HTTPS origin" >&2
    exit 1
fi
if [[ ! "$UPLOAD_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ || ! "$UPLOAD_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
    echo "Upload timeout and attempt count must be positive integers" >&2
    exit 1
fi
for command in curl git jq mktemp sha256sum stat; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done

if [[ -n "${ZAPRET_FORGEJO_TOKEN:-}" ]]; then
    FORGEJO_TOKEN="$ZAPRET_FORGEJO_TOKEN"
else
    if [[ ! -f "$TOKEN_FILE" || "$(stat -c '%a' "$TOKEN_FILE")" != 600 ]]; then
        echo "Forgejo token file must exist with mode 600: $TOKEN_FILE" >&2
        exit 1
    fi
    IFS= read -r FORGEJO_TOKEN < "$TOKEN_FILE"
fi
if [[ -z "$FORGEJO_TOKEN" || "$FORGEJO_TOKEN" == *$'\n'* ]]; then
    echo "Forgejo token is empty or malformed" >&2
    exit 1
fi

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
if ! git show-ref --verify --quiet "refs/tags/$TAG" ||
    [[ "$(git rev-list -n 1 "$TAG")" != "$(git rev-parse HEAD)" ]]; then
    echo "Stable tag must exist and point to the current commit: $TAG" >&2
    exit 1
fi

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$TMP_ROOT"' EXIT

api_curl() {
    curl --silent --show-error \
        --connect-timeout 20 \
        --max-time "$UPLOAD_TIMEOUT_SECONDS" \
        -H "Authorization: token $FORGEJO_TOKEN" \
        -H 'Accept: application/json' \
        "$@"
}

release_json() {
    local output="$TMP_ROOT/release.json" code
    code="$(
        api_curl \
            --output "$output" \
            --write-out '%{http_code}' \
            "$FORGEJO_API_URL/repos/$RELEASE_REPOSITORY/releases/tags/$TAG"
    )"
    case "$code" in
        200)
            jq -ce . "$output"
            ;;
        404)
            return 1
            ;;
        *)
            echo "Forgejo release lookup failed with HTTP $code" >&2
            return 2
            ;;
    esac
}

verify_release_identity() {
    local json="$1" expected_draft="$2"
    jq -e \
        --arg tag "$TAG" \
        --argjson draft "$expected_draft" \
        '.tag_name == $tag and .draft == $draft and .prerelease == false' \
        <<<"$json" >/dev/null || {
        echo "Forgejo Release has an unexpected tag, state, or prerelease flag: $TAG" >&2
        exit 1
    }
}

remote_asset_count() {
    local json="$1" name="$2"
    jq -r --arg name "$name" '[.assets[] | select(.name == $name)] | length' <<<"$json"
}

remote_asset_matches() {
    local json="$1" file="$2" name size url encoded_tag expected_prefix downloaded
    name="$(basename "$file")"
    size="$(stat -c '%s' "$file")"
    if [[ "$(remote_asset_count "$json" "$name")" -ne 1 ]]; then
        return 1
    fi
    if ! jq -e --arg name "$name" --argjson size "$size" \
        '[.assets[] | select(.name == $name)] | length == 1 and .[0].size == $size' \
        <<<"$json" >/dev/null; then
        return 1
    fi
    url="$(jq -r --arg name "$name" '.assets[] | select(.name == $name) | .browser_download_url' <<<"$json")"
    encoded_tag="$(jq -nr --arg value "$TAG" '$value | @uri')"
    expected_prefix="$FORGEJO_BASE_URL/$RELEASE_REPOSITORY/releases/download/$encoded_tag/"
    if [[ "$url" != "$expected_prefix"* ]]; then
        echo "Forgejo returned an asset URL outside the expected repository: $name" >&2
        exit 1
    fi
    downloaded="$TMP_ROOT/download-$name"
    rm -f -- "$downloaded"
    api_curl --fail --location --proto '=https' --max-redirs 3 --output "$downloaded" "$url"
    [[ "$(stat -c '%s' "$downloaded")" == "$size" ]] &&
        [[ "$(sha256sum "$downloaded" | awk '{print $1}')" == "$(sha256sum "$file" | awk '{print $1}')" ]]
}

assert_remote_asset_safe() {
    local json="$1" file="$2" name count
    name="$(basename "$file")"
    count="$(remote_asset_count "$json" "$name")"
    if [[ "$count" -eq 0 ]]; then
        return 1
    fi
    if [[ "$count" -ne 1 ]] || ! remote_asset_matches "$json" "$file"; then
        echo "Remote asset exists but does not match the local bytes: $name" >&2
        echo "Refusing to delete or replace it; publish a correction under a new tag." >&2
        exit 1
    fi
}

create_draft() {
    local payload output code target
    target="$(git rev-parse HEAD)"
    payload="$(jq -n \
        --arg tag "$TAG" \
        --arg target "$target" \
        --arg name "Zapret KVN $VERSION_NAME" \
        --rawfile body "$NOTES_FILE" \
        '{tag_name:$tag,target_commitish:$target,name:$name,body:$body,draft:true,prerelease:false}')"
    output="$TMP_ROOT/create.json"
    code="$(
        api_curl \
            --request POST \
            -H 'Content-Type: application/json' \
            --data "$payload" \
            --output "$output" \
            --write-out '%{http_code}' \
            "$FORGEJO_API_URL/repos/$RELEASE_REPOSITORY/releases"
    )"
    if [[ "$code" != 201 ]]; then
        echo "Forgejo draft creation failed with HTTP $code" >&2
        exit 1
    fi
    jq -ce . "$output"
}

upload_one_asset() {
    local release_id="$1" file="$2" name="$3" attempt status json encoded_name
    encoded_name="$(jq -nr --arg value "$name" '$value | @uri')"
    for ((attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++)); do
        echo "Uploading $name (attempt $attempt/$UPLOAD_ATTEMPTS)"
        status=0
        api_curl \
            --fail \
            --request POST \
            -F "attachment=@$file" \
            "$FORGEJO_API_URL/repos/$RELEASE_REPOSITORY/releases/$release_id/assets?name=$encoded_name" \
            > "$TMP_ROOT/upload.json" || status=$?
        json="$(release_json)" || {
            echo "Could not read the draft after uploading $name" >&2
            exit 1
        }
        verify_release_identity "$json" true
        if remote_asset_matches "$json" "$file"; then
            echo "Verified remote bytes: $name"
            return 0
        fi
        if [[ "$(remote_asset_count "$json" "$name")" -ne 0 ]]; then
            assert_remote_asset_safe "$json" "$file"
        fi
        if (( attempt == UPLOAD_ATTEMPTS )); then
            echo "Upload did not produce a verified remote asset: $name (curl status $status)" >&2
            return 1
        fi
    done
}

release_status=0
json="$(release_json)" || release_status=$?
if [[ "$release_status" -eq 0 ]]; then
    verify_release_identity "$json" true
    echo "Resuming existing stable draft: $TAG"
elif [[ "$release_status" -eq 1 ]]; then
    json="$(create_draft)"
    verify_release_identity "$json" true
    echo "Created stable draft: $TAG"
else
    echo "Could not determine whether Forgejo Release $TAG already exists" >&2
    exit 1
fi
release_id="$(jq -er '.id' <<<"$json")"

for name in "${EXPECTED_NAMES[@]}"; do
    file="$BUNDLE_DIR/$name"
    json="$(release_json)"
    verify_release_identity "$json" true
    if assert_remote_asset_safe "$json" "$file"; then
        echo "Reusing verified remote asset: $name"
    else
        upload_one_asset "$release_id" "$file" "$name"
    fi
done

json="$(release_json)"
verify_release_identity "$json" true
mapfile -t REMOTE_NAMES < <(jq -r '.assets[].name' <<<"$json" | sort)
if [[ "${REMOTE_NAMES[*]}" != "${EXPECTED_NAMES[*]}" ]]; then
    echo "Draft asset set differs from the required eight stable files" >&2
    printf 'Expected: %s\nActual: %s\n' "${EXPECTED_NAMES[*]}" "${REMOTE_NAMES[*]}" >&2
    exit 1
fi
for name in "${EXPECTED_NAMES[@]}"; do
    assert_remote_asset_safe "$json" "$BUNDLE_DIR/$name"
done

payload="$(jq -n \
    --arg name "Zapret KVN $VERSION_NAME" \
    --rawfile body "$NOTES_FILE" \
    '{name:$name,body:$body,draft:false,prerelease:false}')"
code="$(
    api_curl \
        --request PATCH \
        -H 'Content-Type: application/json' \
        --data "$payload" \
        --output "$TMP_ROOT/publish.json" \
        --write-out '%{http_code}' \
        "$FORGEJO_API_URL/repos/$RELEASE_REPOSITORY/releases/$release_id"
)"
if [[ "$code" != 200 ]]; then
    echo "Forgejo publication failed with HTTP $code" >&2
    exit 1
fi

json="$(release_json)"
verify_release_identity "$json" false
mapfile -t REMOTE_NAMES < <(jq -r '.assets[].name' <<<"$json" | sort)
[[ "${REMOTE_NAMES[*]}" == "${EXPECTED_NAMES[*]}" ]]
for name in "${EXPECTED_NAMES[@]}"; do
    assert_remote_asset_safe "$json" "$BUNDLE_DIR/$name"
done
latest_tag="$(api_curl --fail "$FORGEJO_API_URL/repos/$RELEASE_REPOSITORY/releases/latest" | jq -er '.tag_name')"
if [[ "$latest_tag" != "$TAG" ]]; then
    echo "Forgejo latest release is $latest_tag instead of $TAG" >&2
    exit 1
fi

echo "Published immutable stable release: $(jq -r .html_url <<<"$json")"
