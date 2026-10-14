#!/usr/bin/env python3
"""Validate scripts/release-changes.txt against the Telegram bot's changelog rules.

The same items feed the Forgejo release notes and the Telegram announcement.
The bot (zapretgpt release_changelog.normalize_changes) receives them joined
by "; " inside one shell-quoted command and splits them again on ; | • and
newlines. An item that itself contains one of those separators turns into
several items on the bot side — v0.5.0 had 6 items that became 9 and the
announcement was rejected with HTTP 400 after the Forgejo release was already
public. Fail before publication instead.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MAX_ITEMS = 6
MAX_ITEM_CHARS = 180
MAX_TOTAL_CHARS = 700
SEPARATORS = re.compile(r"[;|•]")


def load_items(path: Path) -> list[str]:
    items = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        items.append(" ".join(line.split()).strip(" -•\t"))
    return items


def problems(items: list[str]) -> list[str]:
    found = []
    if not 1 <= len(items) <= MAX_ITEMS:
        found.append(f"нужно от 1 до {MAX_ITEMS} пунктов, сейчас {len(items)}")
    for number, item in enumerate(items, start=1):
        if len(item) > MAX_ITEM_CHARS:
            found.append(f"пункт {number}: {len(item)} символов, максимум {MAX_ITEM_CHARS}")
        if SEPARATORS.search(item):
            found.append(f"пункт {number}: символы ; | • делят пункт в Telegram — замените запятой")
        if "'" in item or '"' in item:
            found.append(f"пункт {number}: прямые кавычки ломают команду бота — используйте «ёлочки»")
    if len("; ".join(items)) > MAX_TOTAL_CHARS:
        found.append(f"всего {len('; '.join(items))} символов, максимум {MAX_TOTAL_CHARS}")
    bot_view = "\n".join(f"• {item}" for item in items)
    if len(bot_view) > MAX_TOTAL_CHARS:
        found.append(f"в Telegram выйдет {len(bot_view)} символов, максимум {MAX_TOTAL_CHARS}")
    return found


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("release-changes.txt")
    found = problems(load_items(path))
    if found:
        print(f"{path}: changelog не пройдёт в Telegram:", file=sys.stderr)
        for problem in found:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("Release changelog fits the Forgejo notes and the Telegram announcement.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
