#!/usr/bin/env bash
set -euo pipefail

# Подготовка ядер к стабильному релизу Android одной командой.
#
# Версии ядер у Windows и Android замораживаются парой. Скрипт сам находит
# последний стабильный релиз Windows и запускает общую подготовку: она сверяет
# upstream, при отставании Android перегенерирует серию патчей Hysteria
# (scripts/update_hysteria_core.py) и записывает core-release-freeze.json в оба
# репозитория. Ничего не коммитит и не публикует: изменения нужно проверить
# сборкой и закоммитить до тега.
#
#   scripts/prepare-stable-core.sh v0.4.11

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-}"
WINDOWS_ROOT="${ZAPRET_WINDOWS_ROOT:-$PROJECT_ROOT/../windows}"

if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: $0 vMAJOR.MINOR.PATCH" >&2
    exit 2
fi
[[ -f "$WINDOWS_ROOT/scripts/prepare_core_release.py" ]] || {
    echo "Windows repository not found: $WINDOWS_ROOT (set ZAPRET_WINDOWS_ROOT)" >&2
    exit 1
}

git -C "$WINDOWS_ROOT" fetch --quiet --tags origin
WINDOWS_TAG="$(git -C "$WINDOWS_ROOT" tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n 1)"
[[ "$WINDOWS_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "No stable Windows tag found in $WINDOWS_ROOT" >&2
    exit 1
}

exec python3 "$WINDOWS_ROOT/scripts/prepare_core_release.py" \
    --windows-version "${WINDOWS_TAG#v}" \
    --android-tag "$TAG" \
    --android-root "$PROJECT_ROOT"
