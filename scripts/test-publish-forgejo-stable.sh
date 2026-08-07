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

echo "Forgejo release publisher safety contract verified."
