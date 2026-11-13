#!/usr/bin/env bash
set -euo pipefail

# One command for everything that comes BEFORE `scripts/publish-local-stable.sh`, in the order that fails fastest,
# so that a stable release does not stop halfway on a stale rule set, a red unit test or missing git credentials
# (all three stopped v0.5.44 on 2026-11-13, one after another).
#
# Usage:
#   scripts/prepare-release.sh vMAJOR.MINOR.PATCH            # prepare, commit, push, tag
#   scripts/prepare-release.sh vMAJOR.MINOR.PATCH --check    # read-only: report what would be done, change nothing
#   scripts/prepare-release.sh vMAJOR.MINOR.PATCH --publish  # prepare, then run the publisher
#
# Starting point: the source change is already committed and pushed, scripts/release-changes.txt is final, and the
# worktree is clean. What the script does, stopping at the first failure:
#   1. checks: main, clean worktree, HEAD == origin/main, the tag is free, the changelog fits, git push works
#      (dry run, through the project's credential helper) and the Windows repository is clean and current;
#   2. ALL unit tests of every module (`testDebugUnitTest`) and `scripts/verify-project.sh`;
#   3. the packaged rule sets: refreshed and committed when upstream moved (the publisher refuses a stale one);
#   4. `scripts/prepare-stable-core.sh TAG` (pairs the Android tag with the latest stable Windows tag), then one
#      freeze commit per repository, both pushed;
#   5. the lightweight tag on the final commit (the publisher pushes it itself, after the draft holds every asset).
# It never edits source files, never touches signing material and never publishes.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WINDOWS_ROOT="${ZAPRET_WINDOWS_ROOT:-$PROJECT_ROOT/../windows}"

TAG="${1:-}"
MODE=prepare
for arg in "${@:2}"; do
    case "$arg" in
        --check) MODE=check ;;
        --publish) MODE=publish ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done
if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: $0 vMAJOR.MINOR.PATCH [--check|--publish]" >&2
    exit 2
fi

# shellcheck disable=SC1091
[[ -f /etc/profile.d/kvn-tools.sh ]] && source /etc/profile.d/kvn-tools.sh
export LC_ALL="${LC_ALL:-C.UTF-8}" PYTHONDONTWRITEBYTECODE=1
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/forgejo-git.sh"
forgejo_git_credentials

step() { printf '\n== %s\n' "$*"; }
die() { echo "prepare-release: $*" >&2; exit 1; }

# ---------------------------------------------------------------- 1. checks
repo_clean() {
    local root="$1"
    git -C "$root" diff --quiet && git -C "$root" diff --cached --quiet &&
        [[ -z "$(git -C "$root" ls-files --others --exclude-standard)" ]]
}

step "1. checks for $TAG"
cd "$PROJECT_ROOT"
[[ "$(git branch --show-current)" == main ]] || die "stable releases are prepared on main"
repo_clean "$PROJECT_ROOT" || die "the Android worktree is not clean: commit or remove the changes first"
git fetch -q origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "local main differs from origin/main: push or integrate first"
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
    die "tag $TAG already exists on Forgejo: use a new version"
fi
if git show-ref --verify --quiet "refs/tags/$TAG" && [[ "$(git rev-list -n 1 "$TAG")" != "$(git rev-parse HEAD)" ]]; then
    echo "note: local tag $TAG points elsewhere, it will be moved to the final commit"
fi
python3 "$SCRIPT_DIR/check-release-changes.py" "$SCRIPT_DIR/release-changes.txt" || die "fix scripts/release-changes.txt"
forgejo_git_auth_check "HEAD:refs/heads/main" || die "git push is not authorized"
echo "git push to Forgejo: authorized"
if [[ -d "$WINDOWS_ROOT/.git" ]]; then
    repo_clean "$WINDOWS_ROOT" || die "the Windows worktree is not clean (another session works there?): not touching it"
    git -C "$WINDOWS_ROOT" fetch -q origin main
    [[ "$(git -C "$WINDOWS_ROOT" rev-parse HEAD)" == "$(git -C "$WINDOWS_ROOT" rev-parse origin/main)" ]] ||
        die "Windows main differs from origin/main: push or integrate it first"
    echo "Windows repository: clean and current"
    "$SCRIPT_DIR/check-shared-routing-list.sh" || die "the shared always-proxy list differs between the repositories"
else
    die "Windows repository not found at $WINDOWS_ROOT (the core pair needs it)"
fi

RULESETS_STALE=0
python3 "$SCRIPT_DIR/refresh_rule_sets.py" --check >/dev/null 2>&1 || RULESETS_STALE=1
if [[ "$MODE" == check ]]; then
    echo "rule sets: $([[ "$RULESETS_STALE" == 1 ]] && echo 'STALE, would be refreshed and committed' || echo 'current')"
    echo
    echo "check passed: nothing was changed. Without --check the script would run all unit tests, verify-project,"
    echo "refresh the rule sets if stale, prepare the core pair, commit and push both freezes and tag $TAG."
    exit 0
fi

# ---------------------------------------------------------------- 2. tests
step "2. all unit tests and project verification"
"$PROJECT_ROOT/gradlew" -q testDebugUnitTest
"$SCRIPT_DIR/verify-project.sh"

# ---------------------------------------------------------------- 3. rule sets
step "3. rule sets"
if [[ "$RULESETS_STALE" == 1 ]]; then
    python3 "$SCRIPT_DIR/refresh_rule_sets.py" --write
    "$SCRIPT_DIR/verify-rule-sets.sh"
    git add -A app/src/main/assets/rule-sets app/src/main/res/raw/third_party_notices.txt docs/RULESETS.md docs/THIRD_PARTY_NOTICES.md
    git commit -q -m "Refresh the packaged rule sets before the stable release"
    echo "rule sets refreshed and committed"
else
    echo "rule sets are current"
fi

# ---------------------------------------------------------------- 4. core pair
step "4. core pair"
"$SCRIPT_DIR/prepare-stable-core.sh" "$TAG"
WINDOWS_VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releases"]["windows"])' "$PROJECT_ROOT/core-release-freeze.json")"
if ! repo_clean "$WINDOWS_ROOT"; then
    git -C "$WINDOWS_ROOT" add core-release-freeze.json scripts/core-lock.windows-x64.json
    git -C "$WINDOWS_ROOT" commit -q -m "Core freeze of the pair: Windows $WINDOWS_VERSION + Android $TAG"
    git -C "$WINDOWS_ROOT" push -q origin main
    echo "Windows freeze committed and pushed"
fi
if ! repo_clean "$PROJECT_ROOT"; then
    git add core-release-freeze.json
    git commit -q -m "Release $TAG: core freeze of the pair"
fi
git push -q origin main

# ---------------------------------------------------------------- 5. tag
step "5. tag"
git tag -f "$TAG" >/dev/null
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "HEAD differs from origin/main after the push"
echo "tag $TAG on $(git rev-parse --short HEAD) (the publisher pushes it)"

if [[ "$MODE" == publish ]]; then
    step "publish"
    exec "$SCRIPT_DIR/publish-local-stable.sh" "$TAG" --final-gate-approved
fi
echo
echo "Prepared. Next: scripts/publish-local-stable.sh $TAG --final-gate-approved"
