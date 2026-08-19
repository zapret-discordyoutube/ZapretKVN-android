#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:?test tag is required}"
BUNDLE_DIR="${2:?test bundle directory is required}"
REPOSITORY="${3:-${ZAPRET_UPDATE_REPOSITORY:-zapretkvn/ZapretKVN-android}}"
BASE_URL="${ZAPRET_FORGEJO_URL:-https://git.zapret.moe}"
API_URL="$BASE_URL/api/v1"
TOKEN_FILE="${ZAPRET_FORGEJO_TOKEN_FILE:-/home/codex-pve/.config/forgejo/zapret-kvn-android-release-token}"

"$PROJECT_ROOT/scripts/derive-test-version.sh" "$TAG" >/dev/null
"$PROJECT_ROOT/scripts/verify-test-bundle.sh" "$TAG" "$BUNDLE_DIR"
if [[ ! "$REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ || ! "$BASE_URL" =~ ^https://[^/]+$ ]]; then
    echo "Invalid Forgejo repository or origin" >&2
    exit 1
fi
for command in curl git jq mktemp sha256sum stat; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
if [[ -n "${ZAPRET_FORGEJO_TOKEN:-}" ]]; then
    FORGEJO_TOKEN="$ZAPRET_FORGEJO_TOKEN"
else
    [[ -f "$TOKEN_FILE" && "$(stat -c '%a' "$TOKEN_FILE")" == 600 ]] || {
        echo "Forgejo token file must exist with mode 600: $TOKEN_FILE" >&2
        exit 1
    }
    IFS= read -r FORGEJO_TOKEN < "$TOKEN_FILE"
fi
[[ -n "$FORGEJO_TOKEN" ]]

cd "$PROJECT_ROOT"
[[ "$(git branch --show-current)" == main ]]
git diff --quiet && git diff --cached --quiet && [[ -z "$(git ls-files --others --exclude-standard)" ]] || {
    echo "Test publication requires a clean worktree" >&2
    exit 1
}
git fetch origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
    echo "Local main must match origin/main before test publication" >&2
    exit 1
}
git show-ref --verify --quiet "refs/tags/$TAG"
[[ "$(git rev-list -n 1 "$TAG")" == "$(git rev-parse HEAD)" ]]

remote_tag="$(git ls-remote origin "refs/tags/$TAG" | awk 'NR == 1 {print $1}')"
if [[ -z "$remote_tag" ]]; then
    git push origin "refs/tags/$TAG"
elif [[ "$remote_tag" != "$(git rev-parse HEAD)" ]]; then
    echo "Remote tag points to another commit: $TAG" >&2
    exit 1
fi

TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_ROOT"' EXIT
api_curl() {
    curl --silent --show-error --connect-timeout 20 --max-time 180 \
        -H "Authorization: token $FORGEJO_TOKEN" -H 'Accept: application/json' "$@"
}
release_lookup() {
    local code
    code="$(api_curl --output "$TEMP_ROOT/release.json" --write-out '%{http_code}' \
        "$API_URL/repos/$REPOSITORY/releases/tags/$TAG")"
    case "$code" in
        200) jq -ce . "$TEMP_ROOT/release.json" ;;
        404) return 1 ;;
        *) echo "Forgejo release lookup failed with HTTP $code" >&2; return 2 ;;
    esac
}

set +e
release_json="$(release_lookup)"
lookup_status=$?
set -e
if [[ "$lookup_status" -eq 0 ]]; then
    jq -e --arg tag "$TAG" '.tag_name == $tag and .draft == true and .prerelease == true' \
        <<<"$release_json" >/dev/null || {
        echo "Existing test release is not a resumable prerelease draft" >&2
        exit 1
    }
elif [[ "$lookup_status" -eq 1 ]]; then
    payload="$(jq -n --arg tag "$TAG" --arg target "$(git rev-parse HEAD)" \
        --arg name "Zapret KVN ${TAG#v} (emulator-only)" \
        --rawfile body "$BUNDLE_DIR/RELEASE_NOTES.md" \
        '{tag_name:$tag,target_commitish:$target,name:$name,body:$body,draft:true,prerelease:true}')"
    code="$(api_curl --request POST -H 'Content-Type: application/json' --data "$payload" \
        --output "$TEMP_ROOT/create.json" --write-out '%{http_code}' \
        "$API_URL/repos/$REPOSITORY/releases")"
    [[ "$code" == 201 ]] || { echo "Forgejo draft creation failed with HTTP $code" >&2; exit 1; }
    release_json="$(jq -ce . "$TEMP_ROOT/create.json")"
else
    exit 1
fi
release_id="$(jq -er '.id' <<<"$release_json")"

mapfile -t assets < <(find "$BUNDLE_DIR" -mindepth 1 -maxdepth 1 -type f ! -name RELEASE_NOTES.md | sort)
[[ "${#assets[@]}" -eq 5 ]]
for asset in "${assets[@]}"; do
    name="$(basename "$asset")"
    count="$(jq -r --arg name "$name" '[.assets[] | select(.name == $name)] | length' <<<"$release_json")"
    if [[ "$count" -eq 0 ]]; then
        encoded_name="$(jq -nr --arg value "$name" '$value | @uri')"
        code="$(api_curl --request POST -H 'Content-Type: application/octet-stream' \
            --data-binary "@$asset" --output "$TEMP_ROOT/upload.json" --write-out '%{http_code}' \
            "$API_URL/repos/$REPOSITORY/releases/$release_id/assets?name=$encoded_name")"
        [[ "$code" == 201 ]] || { echo "Upload failed for $name with HTTP $code" >&2; exit 1; }
        release_json="$(release_lookup)"
    elif [[ "$count" -ne 1 ]]; then
        echo "Duplicate remote asset: $name" >&2
        exit 1
    fi
    url="$(jq -er --arg name "$name" '.assets[] | select(.name == $name) | .browser_download_url' <<<"$release_json")"
    expected_prefix="$BASE_URL/$REPOSITORY/releases/download/$TAG/"
    [[ "$url" == "$expected_prefix"* ]] || { echo "Unexpected asset URL for $name" >&2; exit 1; }
    downloaded="$TEMP_ROOT/download-$name"
    api_curl --fail --location --proto '=https' --max-redirs 3 --output "$downloaded" "$url"
    [[ "$(stat -c '%s' "$downloaded")" == "$(stat -c '%s' "$asset")" ]]
    [[ "$(sha256sum "$downloaded" | awk '{print $1}')" == "$(sha256sum "$asset" | awk '{print $1}')" ]]
done

mapfile -t remote_names < <(jq -r '.assets[].name' <<<"$release_json" | sort)
mapfile -t local_names < <(printf '%s\n' "${assets[@]##*/}" | sort)
[[ "${remote_names[*]}" == "${local_names[*]}" ]] || {
    echo "Remote test asset set is not exact" >&2
    exit 1
}
payload="$(jq -n --arg name "Zapret KVN ${TAG#v} (emulator-only)" \
    --rawfile body "$BUNDLE_DIR/RELEASE_NOTES.md" '{name:$name,body:$body,draft:false,prerelease:true}')"
code="$(api_curl --request PATCH -H 'Content-Type: application/json' --data "$payload" \
    --output "$TEMP_ROOT/publish.json" --write-out '%{http_code}' \
    "$API_URL/repos/$REPOSITORY/releases/$release_id")"
[[ "$code" == 200 ]]
release_json="$(release_lookup)"
jq -e --arg tag "$TAG" '.tag_name == $tag and .draft == false and .prerelease == true and (.assets | length) == 5' \
    <<<"$release_json" >/dev/null
dispatch_payload="$(jq -n --arg ref "$TAG" --arg tag "$TAG" '{ref:$ref,inputs:{tag:$tag}}')"
dispatch_code="$(api_curl --request POST -H 'Content-Type: application/json' --data "$dispatch_payload" \
    --output "$TEMP_ROOT/dispatch.json" --write-out '%{http_code}' \
    "$API_URL/repos/$REPOSITORY/actions/workflows/test-release-verify.yml/dispatches")"
[[ "$dispatch_code" == 204 ]] || {
    echo "Test verification workflow dispatch failed with HTTP $dispatch_code" >&2
    exit 1
}
echo "Published emulator-only test prerelease: $(jq -r .html_url <<<"$release_json")"
