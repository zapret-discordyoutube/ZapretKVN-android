#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISHER="$PROJECT_ROOT/scripts/publish-forgejo-stable.sh"

bash -n "$PUBLISHER"
grep -Fq '$FORGEJO_API_URL/repos/$RELEASE_REPOSITORY/releases' "$PUBLISHER"
grep -Fq 'draft:true,prerelease:false' "$PUBLISHER"
grep -Fq 'draft:false,prerelease:false' "$PUBLISHER"
grep -Fq 'Refusing to delete or replace it' "$PUBLISHER"
grep -Fq 'sha256sum "$downloaded"' "$PUBLISHER"
grep -Fq 'releases/latest' "$PUBLISHER"
grep -Fq 'ZAPRET_FORGEJO_TOKEN_FILE' "$PUBLISHER"

if grep -Eq '(^|[[:space:]])gh([[:space:]]|$)|api\.github\.com|github-secrets' "$PUBLISHER"; then
    echo "Forgejo publisher still depends on GitHub tooling or credentials" >&2
    exit 1
fi
if grep -Fq 'DELETE' "$PUBLISHER"; then
    echo "Forgejo publisher must never delete or replace release assets" >&2
    exit 1
fi

# The emulator-only test publisher follows the same order: it may push the tag
# only after verifying the exact asset set and before publishing the draft.
TEST_PUBLISHER="$PROJECT_ROOT/scripts/publish-forgejo-test.sh"
line_of() { grep -nF -- "$1" "$TEST_PUBLISHER" | head -n 1 | cut -d: -f1; }
exact_check="$(line_of 'Remote test asset set is not exact')"
tag_push="$(line_of 'git push origin "refs/tags/$TAG"')"
publish_patch="$(line_of 'draft:false,prerelease:true')"
if [[ -z "$exact_check" || -z "$tag_push" || -z "$publish_patch" ]] ||
    (( tag_push < exact_check || tag_push > publish_patch )); then
    echo "Test publisher must push the tag after the complete upload and before publishing" >&2
    exit 1
fi

# --- behaviour against a fake Forgejo whose tag sync publishes drafts at once ---
FAKE="$PROJECT_ROOT/scripts/fake-forgejo.py"
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
TAG=v9.9.9
mkdir -p "$WORK/bin"
printf '#!/usr/bin/env bash\nexec python3 %q "$@"\n' "$FAKE" > "$WORK/bin/curl"
chmod +x "$WORK/bin/curl"

new_world() {
    local world="$1"
    rm -rf -- "$world"
    mkdir -p "$world/forgejo" "$world/bundle"
    : > "$world/forgejo/calls.log"
    git init -q --bare "$world/origin.git"
    printf '#!/usr/bin/env bash\nwhile read -r _old _new ref; do\n  case "$ref" in refs/tags/*) FAKE_FORGEJO_DIR=%q python3 %q tag-sync "${ref#refs/tags/}";; esac\ndone\n' \
        "$world/forgejo" "$FAKE" > "$world/origin.git/hooks/post-receive"
    chmod +x "$world/origin.git/hooks/post-receive"
    git init -q -b main "$world/repo"
    git -C "$world/repo" -c user.email=t@t -c user.name=t commit -q --allow-empty -m release
    git -C "$world/repo" remote add origin "$world/origin.git"
    git -C "$world/repo" push -q origin main
    git -C "$world/repo" tag "$TAG"
    printf 'notes\n' > "$world/bundle/RELEASE_NOTES.md"
    for name in "Zapret-KVN-$TAG-arm64-v8a.apk" "Zapret-KVN-$TAG-armeabi-v7a.apk" "Zapret-KVN-$TAG-x86_64.apk" \
        release-metadata.json release-metadata-v2.json; do
        printf 'payload %s\n' "$name" > "$world/bundle/$name"
    done
    for abi in arm64-v8a armeabi-v7a x86_64; do
        sha256sum "$world/bundle/Zapret-KVN-$TAG-$abi.apk" > "$world/bundle/Zapret-KVN-$TAG-$abi.apk.sha256"
    done
}

publish() {
    local world="$1"
    (cd "$world/repo" && PATH="$WORK/bin:$PATH" FAKE_FORGEJO_DIR="$world/forgejo" ZAPRET_FORGEJO_TOKEN=test \
        ZAPRET_FORGEJO_URL=https://forgejo.test bash "$PUBLISHER" "$TAG" "$world/bundle" owner/repo) \
        > "$world/out.log" 2>&1
}

released() {
    jq -r --arg tag "$TAG" '.releases[] | select(.tag_name == $tag) | "\(.draft) \(.assets | length)"' "$1/forgejo/state.json"
}

fail_test() { echo "publisher behaviour: $*" >&2; cat "$WORLD/out.log" >&2 || true; exit 1; }

# 1. Fresh publication: the tag sync fires the instant the tag is pushed, and
#    by then the draft must already hold all eight assets.
WORLD="$WORK/fresh"; new_world "$WORLD"
publish "$WORLD" || fail_test "fresh publication failed"
[[ "$(released "$WORLD")" == "false 8" ]] || fail_test "fresh release is not a complete published release"
grep -qx "tag-sync $TAG assets=8 draft=true" "$WORLD/forgejo/calls.log" || fail_test "tag reached Forgejo before the draft was complete"
[[ "$(git -C "$WORLD/origin.git" rev-parse "refs/tags/$TAG")" == "$(git -C "$WORLD/repo" rev-parse HEAD)" ]] || fail_test "tag not pushed"

# 2. Resume after publication (the sync already published it): verify only.
: > "$WORLD/forgejo/calls.log"
publish "$WORLD" || fail_test "resume of a complete published release failed"
! grep -q '^upload\|^create\|^publish' "$WORLD/forgejo/calls.log" || fail_test "resume changed a published release"

# 3. Resume a draft interrupted mid-upload: reuse verified assets, finish.
WORLD="$WORK/partial"; new_world "$WORLD"
(cd "$WORLD/repo" && PATH="$WORK/bin:$PATH" FAKE_FORGEJO_DIR="$WORLD/forgejo" python3 "$FAKE" --request POST \
    --data '{"tag_name":"'"$TAG"'","draft":true,"prerelease":false}' --output /dev/null \
    https://forgejo.test/api/v1/repos/owner/repo/releases)
for name in release-metadata.json release-metadata-v2.json; do
    PATH="$WORK/bin:$PATH" FAKE_FORGEJO_DIR="$WORLD/forgejo" python3 "$FAKE" --fail --request POST \
        -F "attachment=@$WORLD/bundle/$name" "https://forgejo.test/api/v1/repos/owner/repo/releases/1/assets?name=$name" >/dev/null
done
: > "$WORLD/forgejo/calls.log"
publish "$WORLD" || fail_test "resume of a partial draft failed"
[[ "$(released "$WORLD")" == "false 8" ]] || fail_test "resumed draft was not completed"
[[ "$(grep -c '^upload' "$WORLD/forgejo/calls.log")" == 6 ]] || fail_test "resume re-uploaded verified assets"
grep -qx "tag-sync $TAG assets=8 draft=true" "$WORLD/forgejo/calls.log" || fail_test "resumed tag pushed before completion"

# 4. A published release with a partial asset set is never altered.
WORLD="$WORK/incomplete"; new_world "$WORLD"
(cd "$WORLD/repo" && PATH="$WORK/bin:$PATH" FAKE_FORGEJO_DIR="$WORLD/forgejo" python3 "$FAKE" --request POST \
    --data '{"tag_name":"'"$TAG"'","draft":false,"prerelease":false}' --output /dev/null \
    https://forgejo.test/api/v1/repos/owner/repo/releases)
: > "$WORLD/forgejo/calls.log"
if publish "$WORLD"; then fail_test "an incomplete published release was accepted"; fi
grep -q 'published with an incomplete asset set' "$WORLD/out.log" || fail_test "incomplete release refused without a clear reason"
! grep -q '^upload\|^publish' "$WORLD/forgejo/calls.log" || fail_test "incomplete published release was modified"

# 5. A tag already on Forgejo without a draft would publish a new draft
#    mid-upload: refuse before creating anything.
WORLD="$WORK/pushed"; new_world "$WORLD"
git -C "$WORLD/repo" push -q origin "refs/tags/$TAG"
if publish "$WORLD"; then fail_test "publication under an already pushed tag was attempted"; fi
grep -q 'already on Forgejo without a draft' "$WORLD/out.log" || fail_test "pushed-tag refusal lacks a clear reason"
! grep -q '^create' "$WORLD/forgejo/calls.log" || fail_test "draft created for an already pushed tag"

echo "Forgejo release publisher safety contract verified."
