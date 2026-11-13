#!/usr/bin/env bash
# Git over HTTPS to Forgejo for the release scripts, so that a missing credential helper is found in seconds
# instead of at the very last step of a release.
#
# Why (2026-11-13, v0.5.44): the publisher built, signed and uploaded the whole stable bundle and then failed at
# the final `git push` of the tag ("could not read Username"): a panel shell has no `credential.helper`, the project
# helper `~/.config/forgejo/git-credential-forgejo` was never wired in. The release stayed a private draft; a resume
# fixed it, but the failure was avoidable.
#
# Usage (sourced):
#   forgejo_git_credentials          # wires the project helper through the environment when nothing is configured
#   forgejo_git_auth_check REF       # proves that a push of REF is authorized (dry run), before anything long runs
#
# The helper is never printed or read here: git runs it. Nothing is written to any git config file.

# Path of the project's credential helper (overridable for tests).
forgejo_git_helper_path() {
    printf '%s\n' "${FORGEJO_GIT_CREDENTIAL_HELPER:-$HOME/.config/forgejo/git-credential-forgejo}"
}

# Adds `credential.helper=<project helper>` to this process and its children through GIT_CONFIG_*,
# unless git already has a helper configured (an existing setup always wins) or the helper does not exist.
# Idempotent: a second call changes nothing.
forgejo_git_credentials() {
    local helper count
    helper="$(forgejo_git_helper_path)"
    [[ -z "${FORGEJO_GIT_WIRED:-}" ]] || return 0
    if [[ -n "$(git config --get-all credential.helper 2>/dev/null || true)" ]]; then
        return 0
    fi
    if [[ ! -x "$helper" ]]; then
        return 0
    fi
    count="${GIT_CONFIG_COUNT:-0}"
    export "GIT_CONFIG_KEY_${count}=credential.helper"
    export "GIT_CONFIG_VALUE_${count}=${helper}"
    export GIT_CONFIG_COUNT="$((count + 1))"
    export FORGEJO_GIT_WIRED=1
}

# Dry-run push of REF to origin: asks the server for its receive-pack advertisement, which needs the same
# credentials as the real push, and changes nothing. Returns non-zero (with a clear message) when it fails.
forgejo_git_auth_check() {
    local ref="${1:?ref required}" output
    if output="$(GIT_TERMINAL_PROMPT=0 git push --dry-run origin "$ref" 2>&1)"; then
        return 0
    fi
    {
        echo "git push to Forgejo is not authorized (dry run of $ref failed):"
        printf '%s\n' "$output" | sed 's/^/    /' | head -n 6
        echo "The release scripts use $(forgejo_git_helper_path); it is missing, not executable or its token is invalid."
    } >&2
    return 1
}
