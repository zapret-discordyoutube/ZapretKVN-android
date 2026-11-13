#!/usr/bin/env bash
set -euo pipefail

# Offline tests for scripts/lib/forgejo-git.sh: a throwaway repository with a local bare "origin" and fake
# credential helpers. The real helper, the real tokens and the network are never touched.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

export HOME="$WORK_DIR/home"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_CONFIG_NOSYSTEM=1
mkdir -p "$HOME"
git init -q --bare "$WORK_DIR/origin.git"
git init -q "$WORK_DIR/repo"
cd "$WORK_DIR/repo"
git config user.email test@example.invalid
git config user.name test
git remote add origin "$WORK_DIR/origin.git"
git commit -q --allow-empty -m first
git tag v0.0.1

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/forgejo-git.sh"

run() {
    # Fresh environment per case: no wiring left over from the previous one.
    ( unset GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0 FORGEJO_GIT_WIRED; "$@" )
}

# 1. No helper file and nothing configured: nothing is wired and nothing breaks.
export FORGEJO_GIT_CREDENTIAL_HELPER="$WORK_DIR/missing-helper"
out="$(run bash -c 'source "$1"; forgejo_git_credentials; echo "count=${GIT_CONFIG_COUNT:-0}"' _ "$SCRIPT_DIR/lib/forgejo-git.sh")"
[[ "$out" == "count=0" ]] || fail "a missing helper must not be wired: $out"
echo "ok: a missing helper is not wired"

# 2. A helper exists and git has none: it is wired through the environment, once.
printf '#!/bin/sh\nexit 0\n' > "$WORK_DIR/helper"
chmod +x "$WORK_DIR/helper"
export FORGEJO_GIT_CREDENTIAL_HELPER="$WORK_DIR/helper"
out="$(run bash -c 'source "$1"; forgejo_git_credentials; forgejo_git_credentials; echo "count=$GIT_CONFIG_COUNT key=$GIT_CONFIG_KEY_0 value=$GIT_CONFIG_VALUE_0"; git config --get credential.helper' _ "$SCRIPT_DIR/lib/forgejo-git.sh")"
[[ "$out" == *"count=1 key=credential.helper value=$WORK_DIR/helper"* ]] || fail "helper not wired once: $out"
[[ "$out" == *"$WORK_DIR/helper"* ]] || fail "git does not see the wired helper: $out"
echo "ok: the helper is wired once and git sees it"

# 3. An existing configured helper wins and is never overridden.
git config credential.helper "store --file=$WORK_DIR/none"
out="$(run bash -c 'source "$1"; forgejo_git_credentials; echo "count=${GIT_CONFIG_COUNT:-0}"' _ "$SCRIPT_DIR/lib/forgejo-git.sh")"
[[ "$out" == "count=0" ]] || fail "an existing helper must win: $out"
git config --unset-all credential.helper
echo "ok: an existing helper is kept"

# 4. Existing GIT_CONFIG_* entries are kept and the new one is appended after them.
out="$(GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.abbrev GIT_CONFIG_VALUE_0=9 run bash -c 'export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.abbrev GIT_CONFIG_VALUE_0=9; source "$1"; forgejo_git_credentials; echo "count=$GIT_CONFIG_COUNT k0=$GIT_CONFIG_KEY_0 k1=$GIT_CONFIG_KEY_1"' _ "$SCRIPT_DIR/lib/forgejo-git.sh")"
[[ "$out" == "count=2 k0=core.abbrev k1=credential.helper" ]] || fail "existing GIT_CONFIG entries not kept: $out"
echo "ok: existing GIT_CONFIG entries are kept"

# 5. The dry-run check: works against a reachable origin and leaves it untouched.
forgejo_git_auth_check refs/tags/v0.0.1 || fail "dry run to a reachable origin must pass"
[[ -z "$(git ls-remote origin 'refs/tags/*')" ]] || fail "the dry run must not push anything"
echo "ok: the dry run passes and pushes nothing"

# 6. The dry-run check fails with a clear message when origin cannot be reached or authorized.
git remote set-url origin "$WORK_DIR/does-not-exist.git"
if msg="$(forgejo_git_auth_check refs/tags/v0.0.1 2>&1)"; then
    fail "an unreachable origin must fail the check"
fi
[[ "$msg" == *"git push to Forgejo is not authorized"* && "$msg" == *"$WORK_DIR/helper"* ]] || fail "unclear message: $msg"
echo "ok: a failing dry run explains itself"

echo "test-forgejo-git: all passed"
