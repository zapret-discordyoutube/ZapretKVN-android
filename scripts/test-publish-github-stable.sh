#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISHER="$PROJECT_ROOT/scripts/publish-github-stable.sh"
TAG="v9.8.7"
REPOSITORY="example/release-test"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$TEST_ROOT"' EXIT

BUNDLE_DIR="$TEST_ROOT/bundle"
MOCK_BIN="$TEST_ROOT/bin"
MOCK_STATE="$TEST_ROOT/state"
mkdir -p "$BUNDLE_DIR" "$MOCK_BIN" "$MOCK_STATE"

ASSET_NAMES=(
    "Zapret-KVN-$TAG-arm64-v8a.apk"
    "Zapret-KVN-$TAG-arm64-v8a.apk.sha256"
    "Zapret-KVN-$TAG-armeabi-v7a.apk"
    "Zapret-KVN-$TAG-armeabi-v7a.apk.sha256"
    "Zapret-KVN-$TAG-x86_64.apk"
    "Zapret-KVN-$TAG-x86_64.apk.sha256"
    "release-metadata-v2.json"
    "release-metadata.json"
)
for name in "${ASSET_NAMES[@]}"; do
    printf 'fixture:%s\n' "$name" > "$BUNDLE_DIR/$name"
done
printf 'Release test notes\n' > "$BUNDLE_DIR/RELEASE_NOTES.md"

cat > "$MOCK_BIN/gh" <<'MOCK_GH'
#!/usr/bin/env bash
set -euo pipefail

STATE_DIR="${MOCK_GH_STATE:?}"
TAG="${MOCK_GH_TAG:?}"
STATE_FILE="$STATE_DIR/release-state"
ASSETS_FILE="$STATE_DIR/assets.tsv"
LOG_FILE="$STATE_DIR/calls.log"

release_json() {
    local state draft assets name size digest
    [[ -f "$STATE_FILE" ]] || exit 1
    state="$(<"$STATE_FILE")"
    draft=false
    [[ "$state" == draft ]] && draft=true
    assets='[]'
    if [[ -f "$ASSETS_FILE" ]]; then
        while IFS=$'\t' read -r name size digest; do
            [[ -n "$name" ]] || continue
            assets="$(
                jq -c \
                    --arg name "$name" \
                    --argjson size "$size" \
                    --arg digest "$digest" \
                    '. + [{name: $name, size: $size, state: "uploaded", digest: $digest}]' \
                    <<<"$assets"
            )"
        done < "$ASSETS_FILE"
    fi
    jq -n \
        --arg tag "$TAG" \
        --argjson draft "$draft" \
        --argjson assets "$assets" \
        '{
            tag_name: $tag,
            draft: $draft,
            prerelease: false,
            assets: $assets,
            html_url: ("https://example.invalid/releases/tag/" + $tag)
        }'
}

case "${1:-} ${2:-}" in
    "api repos/"*)
        release_json
        ;;
    "api --paginate")
        [[ "${3:-}" == repos/* ]]
        release_json | jq -s '.'
        ;;
    "release create")
        [[ ! -e "$STATE_FILE" ]]
        printf 'draft\n' > "$STATE_FILE"
        printf 'CREATE\n' >> "$LOG_FILE"
        printf 'https://example.invalid/draft\n'
        ;;
    "release upload")
        [[ "$#" -eq 6 && "${5:-}" == --repo ]]
        [[ "$(<"$STATE_FILE")" == draft ]]
        asset="${4:?}"
        name="$(basename "$asset")"
        printf 'UPLOAD\t%s\n' "$name" >> "$LOG_FILE"
        if [[ "${MOCK_GH_FAIL_ASSET:-}" == "$name" ]]; then
            exit 23
        fi
        if [[ -f "$ASSETS_FILE" ]] && cut -f1 "$ASSETS_FILE" | grep -Fx "$name" >/dev/null; then
            echo "duplicate asset: $name" >&2
            exit 1
        fi
        printf '%s\t%s\tsha256:%s\n' \
            "$name" \
            "$(stat -c '%s' "$asset")" \
            "$(sha256sum "$asset" | awk '{print $1}')" \
            >> "$ASSETS_FILE"
        ;;
    "release edit")
        [[ "$(<"$STATE_FILE")" == draft ]]
        printf 'published\n' > "$STATE_FILE"
        printf 'EDIT\n' >> "$LOG_FILE"
        printf 'https://example.invalid/releases/tag/%s\n' "$TAG"
        ;;
    *)
        echo "Unexpected mock gh call: $*" >&2
        exit 1
        ;;
esac
MOCK_GH
chmod 700 "$MOCK_BIN/gh"

export PATH="$MOCK_BIN:$PATH"
export MOCK_GH_STATE="$MOCK_STATE"
export MOCK_GH_TAG="$TAG"
export ZAPRET_RELEASE_UPLOAD_TIMEOUT_SECONDS=5
export ZAPRET_RELEASE_UPLOAD_ATTEMPTS=1

export MOCK_GH_FAIL_ASSET="Zapret-KVN-$TAG-armeabi-v7a.apk"
if "$PUBLISHER" "$TAG" "$BUNDLE_DIR" "$REPOSITORY" >/dev/null 2>&1; then
    echo "Interrupted publication test unexpectedly succeeded" >&2
    exit 1
fi
[[ "$(<"$MOCK_STATE/release-state")" == draft ]]
[[ "$(grep -c '^CREATE$' "$MOCK_STATE/calls.log")" -eq 1 ]]
[[ "$(grep -c '^EDIT$' "$MOCK_STATE/calls.log" || true)" -eq 0 ]]

unset MOCK_GH_FAIL_ASSET
"$PUBLISHER" "$TAG" "$BUNDLE_DIR" "$REPOSITORY" >/dev/null
[[ "$(<"$MOCK_STATE/release-state")" == published ]]
[[ "$(grep -c '^CREATE$' "$MOCK_STATE/calls.log")" -eq 1 ]]
[[ "$(grep -c $'^UPLOAD\t' "$MOCK_STATE/calls.log")" -eq 9 ]]
[[ "$(grep -c '^EDIT$' "$MOCK_STATE/calls.log")" -eq 1 ]]
[[ "$(wc -l < "$MOCK_STATE/assets.tsv")" -eq 8 ]]

if "$PUBLISHER" "$TAG" "$BUNDLE_DIR" "$REPOSITORY" >/dev/null 2>&1; then
    echo "Published immutable release was accepted as resumable" >&2
    exit 1
fi
[[ "$(grep -c '^EDIT$' "$MOCK_STATE/calls.log")" -eq 1 ]]

MISMATCH_STATE="$TEST_ROOT/mismatch-state"
mkdir -p "$MISMATCH_STATE"
printf 'draft\n' > "$MISMATCH_STATE/release-state"
printf '%s\t%s\tsha256:%064d\n' \
    "Zapret-KVN-$TAG-arm64-v8a.apk" \
    "$(stat -c '%s' "$BUNDLE_DIR/Zapret-KVN-$TAG-arm64-v8a.apk")" \
    0 \
    > "$MISMATCH_STATE/assets.tsv"
export MOCK_GH_STATE="$MISMATCH_STATE"
if "$PUBLISHER" "$TAG" "$BUNDLE_DIR" "$REPOSITORY" >/dev/null 2>&1; then
    echo "Mismatched remote digest was accepted" >&2
    exit 1
fi
[[ "$(<"$MISMATCH_STATE/release-state")" == draft ]]
[[ ! -e "$MISMATCH_STATE/calls.log" ]]

echo "Resumable sequential GitHub release publication verified."
