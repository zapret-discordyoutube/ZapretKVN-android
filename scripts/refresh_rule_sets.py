#!/usr/bin/env python3
"""Keep the packaged RU IP rule-set on the latest upstream sing-geoip snapshot.

`zapret-ru-ip.srs` is SagerNet/sing-geoip `geoip-ru.srs` from the `rule-set`
branch, pinned by commit in `app/src/main/assets/rule-sets/manifest.json`.
Upstream refreshes it monthly; a stale snapshot silently routes new Russian
ranges through the VPN in «Россия напрямую».

--check  exit 1 when the branch has a newer snapshot than the pin (no writes)
--write  download the file at the exact new commit (immutable raw URL), check
         it with the pinned core CLI (RU/non-RU IPv4/IPv6), then update the
         asset, the manifest and every current-pin reference together
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/rule-sets"
MANIFEST = ASSETS / "manifest.json"
REPOSITORY = "SagerNet/sing-geoip"
BRANCH = "rule-set"
TAG = "zapret-ru-ip"
SOURCE_FILE = "geoip-ru.srs"
#: Files that state the *current* pin (historical analysis in docs is left as is).
PIN_REFERENCES = (
    "app/src/main/res/raw/third_party_notices.txt",
    "docs/THIRD_PARTY_NOTICES.md",
    "docs/RULESETS.md",
)
CLI = ROOT / "core-build/output/sing-box"
EXPECT_MATCH = ("5.255.255.5", "2a02:6b8::feed:0ff")
EXPECT_NO_MATCH = ("1.1.1.1", "2606:4700:4700::1111")


def fetch(url: str, *, timeout: float = 60.0) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "zapret-kvn-android-rule-sets"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def latest_commit() -> str:
    data = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/commits/{BRANCH}"))
    sha = str(data.get("sha", ""))
    if re.fullmatch(r"[0-9a-f]{40}", sha) is None:
        raise SystemExit(f"GitHub returned an invalid commit for {REPOSITORY}@{BRANCH}")
    return sha


def pinned() -> dict:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return next(item for item in manifest["sets"] if item["tag"] == TAG)


def check_with_cli(path: Path) -> None:
    if not CLI.is_file():
        raise SystemExit(f"Pinned core CLI is missing ({CLI}); run scripts/build-core.sh first")
    for value, expected in [(v, True) for v in EXPECT_MATCH] + [(v, False) for v in EXPECT_NO_MATCH]:
        result = subprocess.run([str(CLI), "rule-set", "match", "-f", "binary", str(path), value],
                                capture_output=True, text=True, check=True)
        matched = "match rules." in result.stdout + result.stderr
        if matched != expected:
            raise SystemExit(f"New {SOURCE_FILE} {'misses' if expected else 'wrongly matches'} {value}")


def write(new_commit: str) -> None:
    current = pinned()
    data = fetch(f"https://raw.githubusercontent.com/{REPOSITORY}/{new_commit}/{SOURCE_FILE}")
    digest = hashlib.sha256(data).hexdigest()
    with tempfile.TemporaryDirectory() as directory:
        candidate = Path(directory) / SOURCE_FILE
        candidate.write_bytes(data)
        check_with_cli(candidate)
    old_commit, old_digest = current["source_revision"], current["sha256"]
    updates = {}
    for relative in PIN_REFERENCES:
        path = ROOT / relative
        text = path.read_text(encoding="utf-8")
        if old_commit not in text and old_digest not in text:
            raise SystemExit(f"{relative} no longer names the pinned snapshot; update this tool")
        updates[path] = text.replace(old_commit, new_commit).replace(old_digest, digest)
    manifest_text = MANIFEST.read_text(encoding="utf-8")
    for old, new in ((old_commit, new_commit), (old_digest, digest)):
        if manifest_text.count(old) != 1:
            raise SystemExit("Rule-set manifest format changed; update this tool")
        manifest_text = manifest_text.replace(old, new)
    (ASSETS / current["file"]).write_bytes(data)
    MANIFEST.write_text(manifest_text, encoding="utf-8")
    for path, text in updates.items():
        path.write_text(text, encoding="utf-8")
    print(f"{TAG}: {old_commit[:12]} -> {new_commit[:12]} ({len(data)} bytes, sha256 {digest})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    args = parser.parse_args()
    current, newest = pinned()["source_revision"], latest_commit()
    if current == newest:
        print(f"{TAG} is on the latest {REPOSITORY}@{BRANCH} snapshot {newest[:12]}")
        return 0
    if args.check:
        print(f"{TAG} is stale: pinned {current[:12]}, upstream {newest[:12]}; "
              "run scripts/refresh_rule_sets.py --write", file=sys.stderr)
        return 1
    write(newest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
