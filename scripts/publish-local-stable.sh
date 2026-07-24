#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-}"
APPROVAL="${2:-}"
SIGNING_DIR="${ZAPRET_SIGNING_DIR:-${HOME:?HOME is required}/.zapret-kvn-signing}"
SIGNING_ENV="$SIGNING_DIR/github-secrets.env"
SIGNING_STORE="$SIGNING_DIR/zapret-kvn-release.jks"
SIGNING_FINGERPRINT_FILE="$SIGNING_DIR/certificate-sha256.txt"
RELEASE_MATRIX_DIR="$PROJECT_ROOT/app/build/outputs/apk/matrix/release"
OUTPUT_DIR="$PROJECT_ROOT/build/local-release/$TAG"
STAGING_OUTPUT_DIR="$PROJECT_ROOT/build/local-release/.$TAG.staging"
RELEASE_REPOSITORY="${ZAPRET_UPDATE_REPOSITORY:-youtubediscord/ZapretKVN-android}"

if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ || "$APPROVAL" != --final-gate-approved ]]; then
    echo "Usage: $0 vMAJOR.MINOR.PATCH --final-gate-approved" >&2
    exit 1
fi

for command in gh git jq keytool mv sha256sum stat timeout; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done
if [[ "$(stat -c '%a' "$SIGNING_DIR")" != 700 ]]; then
    echo "Signing directory must have mode 700: $SIGNING_DIR" >&2
    exit 1
fi
for private_file in "$SIGNING_ENV" "$SIGNING_STORE" "$SIGNING_FINGERPRINT_FILE"; do
    if [[ ! -f "$private_file" || "$(stat -c '%a' "$private_file")" != 600 ]]; then
        echo "Signing file must exist with mode 600: $private_file" >&2
        exit 1
    fi
done

cd "$PROJECT_ROOT"
if [[ "$(git branch --show-current)" != main ]]; then
    echo "Stable releases must be published directly from main" >&2
    exit 1
fi
if ! git diff --quiet || ! git diff --cached --quiet ||
    [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
    echo "Stable releases require a clean worktree" >&2
    exit 1
fi

git fetch origin main
if [[ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]]; then
    echo "Local main must exactly match origin/main before publication" >&2
    exit 1
fi
if [[ "$(gh repo view --json nameWithOwner --jq .nameWithOwner)" != "$RELEASE_REPOSITORY" ]]; then
    echo "Current repository does not match the updater release repository" >&2
    exit 1
fi
if ! git show-ref --verify --quiet "refs/tags/$TAG"; then
    echo "Create the stable tag on the release commit before publication: $TAG" >&2
    exit 1
fi
if [[ "$(git rev-list -n 1 "$TAG")" != "$(git rev-parse HEAD)" ]]; then
    echo "Stable tag must point to the current main commit: $TAG" >&2
    exit 1
fi

remote_tag_commit="$(
    {
        git ls-remote origin "refs/tags/$TAG^{}"
        git ls-remote origin "refs/tags/$TAG"
    } | awk 'NR == 1 {print $1}'
)"
if [[ -z "$remote_tag_commit" ]]; then
    git push origin "refs/tags/$TAG"
elif [[ "$remote_tag_commit" != "$(git rev-parse HEAD)" ]]; then
    echo "Remote tag points to a different commit: $TAG" >&2
    exit 1
fi

if release_json="$(
    gh api --paginate "repos/$RELEASE_REPOSITORY/releases?per_page=100" 2>/dev/null \
        | jq -sce \
            --arg tag "$TAG" \
            '
                [.[] | .[] | select(.tag_name == $tag)]
                | if length == 1 then
                    .[0]
                elif length == 0 then
                    empty
                else
                    error("multiple GitHub Releases use the requested tag")
                end
            '
)"; then
    if ! jq -e \
        --arg tag "$TAG" \
        '.tag_name == $tag and .draft == true and .prerelease == false' \
        <<<"$release_json" >/dev/null; then
        echo "GitHub Release already exists and is not a resumable draft: $TAG" >&2
        exit 1
    fi
    if [[ ! -d "$OUTPUT_DIR" ]]; then
        echo "Stable draft exists, but its original local bundle is unavailable: $OUTPUT_DIR" >&2
        echo "Refusing to rebuild potentially different assets or alter the draft." >&2
        exit 1
    fi
    echo "Found resumable stable draft: $TAG"
fi

# This file is owner-only and is the existing canonical local copy of the release
# credentials. Parse assignments as data so the credentials file is never executed.
declare -A signing_values=()
while IFS='=' read -r name value; do
    case "$name" in
        ANDROID_SIGNING_KEYSTORE_BASE64|ANDROID_SIGNING_STORE_PASSWORD|ANDROID_SIGNING_KEY_ALIAS|ANDROID_SIGNING_KEY_PASSWORD|ANDROID_SIGNING_CERT_SHA256)
            if [[ -v "signing_values[$name]" ]]; then
                echo "Duplicate signing variable: $name" >&2
                exit 1
            fi
            signing_values["$name"]="$value"
            ;;
        *)
            echo "Unexpected entry in signing credentials file: $name" >&2
            exit 1
            ;;
    esac
done < "$SIGNING_ENV"
ANDROID_SIGNING_STORE_PASSWORD="${signing_values[ANDROID_SIGNING_STORE_PASSWORD]:-}"
ANDROID_SIGNING_KEY_ALIAS="${signing_values[ANDROID_SIGNING_KEY_ALIAS]:-}"
ANDROID_SIGNING_KEY_PASSWORD="${signing_values[ANDROID_SIGNING_KEY_PASSWORD]:-}"
ANDROID_SIGNING_CERT_SHA256="${signing_values[ANDROID_SIGNING_CERT_SHA256]:-}"
: "${ANDROID_SIGNING_STORE_PASSWORD:?Missing signing store password}"
: "${ANDROID_SIGNING_KEY_ALIAS:?Missing signing key alias}"
: "${ANDROID_SIGNING_KEY_PASSWORD:?Missing signing key password}"
: "${ANDROID_SIGNING_CERT_SHA256:?Missing signing certificate fingerprint}"

# shellcheck disable=SC1091
source "$PROJECT_ROOT/release.properties"
private_fingerprint="$(
    tr '[:upper:]' '[:lower:]' < "$SIGNING_FINGERPRINT_FILE" | tr -d ':[:space:]'
)"
configured_fingerprint="$(
    tr '[:upper:]' '[:lower:]' <<<"$ANDROID_SIGNING_CERT_SHA256" | tr -d ':[:space:]'
)"
export ZAPRET_SIGNING_STORE_PASSWORD="$ANDROID_SIGNING_STORE_PASSWORD"
jks_fingerprint="$(
    keytool -exportcert \
        -keystore "$SIGNING_STORE" \
        -alias "$ANDROID_SIGNING_KEY_ALIAS" \
        -storepass:env ZAPRET_SIGNING_STORE_PASSWORD \
        | sha256sum \
        | awk '{print $1}'
)"
if [[ ! "$RELEASE_SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
    [[ "$private_fingerprint" != "$RELEASE_SIGNER_SHA256" ]] ||
    [[ "$configured_fingerprint" != "$RELEASE_SIGNER_SHA256" ]] ||
    [[ "$jks_fingerprint" != "$RELEASE_SIGNER_SHA256" ]]; then
    echo "Production signing certificate does not match release.properties" >&2
    exit 1
fi

while IFS='=' read -r name value; do
    case "$name" in
        ZAPRET_VERSION_NAME|ZAPRET_VERSION_CODE|ZAPRET_PRERELEASE|ZAPRET_RELEASE_CHANNEL)
            export "$name=$value"
            ;;
        *)
            echo "Unexpected release variable: $name" >&2
            exit 1
            ;;
    esac
done < <("$PROJECT_ROOT/scripts/derive-release-version.sh" "$TAG")
if [[ "$ZAPRET_RELEASE_CHANNEL" != stable || "$ZAPRET_PRERELEASE" != false ]]; then
    echo "Local publisher accepts stable tags only" >&2
    exit 1
fi

latest_tag="$(gh api "repos/$RELEASE_REPOSITORY/releases/latest" --jq .tag_name 2>/dev/null || true)"
if [[ -n "$latest_tag" ]]; then
    latest_version_code="$(
        "$PROJECT_ROOT/scripts/derive-release-version.sh" "$latest_tag" \
            | sed -n 's/^ZAPRET_VERSION_CODE=//p'
    )"
    if (( ZAPRET_VERSION_CODE <= latest_version_code )); then
        echo "Stable versionCode must be newer than the latest published stable" >&2
        exit 1
    fi
fi

export ZAPRET_SIGNING_STORE_FILE="$SIGNING_STORE"
export ZAPRET_SIGNING_KEY_ALIAS="$ANDROID_SIGNING_KEY_ALIAS"
export ZAPRET_SIGNING_KEY_PASSWORD="$ANDROID_SIGNING_KEY_PASSWORD"
export ZAPRET_EXPECTED_SIGNER_SHA256="$RELEASE_SIGNER_SHA256"
export ZAPRET_REQUIRE_SIGNED_RELEASE=1
export ZAPRET_UPDATE_REPOSITORY="$RELEASE_REPOSITORY"

if [[ -e "$OUTPUT_DIR" && ! -d "$OUTPUT_DIR" ]]; then
    echo "Local release output path is not a directory: $OUTPUT_DIR" >&2
    exit 1
fi
if [[ -d "$OUTPUT_DIR" ]]; then
    echo "Reusing existing local release bundle after full verification: $OUTPUT_DIR"
    "$PROJECT_ROOT/scripts/verify-release-bundle.sh" "$TAG" "$OUTPUT_DIR"
else
    if [[ -e "$STAGING_OUTPUT_DIR" ]]; then
        echo "Incomplete staging output exists; move it aside before retrying: $STAGING_OUTPUT_DIR" >&2
        exit 1
    fi
    "$PROJECT_ROOT/scripts/ci-build.sh"
    "$PROJECT_ROOT/scripts/create-release-bundle.sh" \
        "$TAG" \
        "$RELEASE_MATRIX_DIR" \
        "$STAGING_OUTPUT_DIR"
    "$PROJECT_ROOT/scripts/verify-release-bundle.sh" "$TAG" "$STAGING_OUTPUT_DIR"
    mv "$STAGING_OUTPUT_DIR" "$OUTPUT_DIR"
    echo "Promoted verified release bundle atomically: $OUTPUT_DIR"
fi

"$PROJECT_ROOT/scripts/publish-github-stable.sh" \
    "$TAG" \
    "$OUTPUT_DIR" \
    "$RELEASE_REPOSITORY"

if gh workflow run release.yml --repo "$RELEASE_REPOSITORY" --ref main -f "tag=$TAG"; then
    echo "Stable $TAG published; independent GitHub Actions verification was dispatched."
else
    echo "Stable $TAG was published, but background verification dispatch failed." >&2
    exit 1
fi
