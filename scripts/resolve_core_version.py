#!/usr/bin/env python3
"""Pin the sing-box-extended core that the Android build compiles.

Android links sing-box from source with the ordered patchset in core-patches/,
so a new upstream release is accepted only when that exact patchset still
applies and the build contract still matches. Everything is checked in a
private temporary checkout; a failed check leaves core.properties untouched.

  --check [--require-current]  compare the pin with the latest stable release
  --write [--tag T --commit C] validate the candidate, then rewrite the pin

A new stable tag that breaks the patchset fails closed: the core patches must
be ported (rebase core-patches onto the new tag) before a stable release.

The coordinated release step (windows/scripts/prepare_core_release.py) imports
validate_candidate() and pin_changes() so both platforms follow one upstream
selection; this CLI is the standalone form of the same check.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
GITHUB_REPOSITORY = "shtorm-7/sing-box-extended"
USER_AGENT = "ZapretKVN-android-core-resolver/1"
TAG_PATTERN = re.compile(r"^v(\d+)\.(\d+)\.(\d+)-extended-(\d+)\.(\d+)\.(\d+)$")
PACKAGED_LICENSE = "app/src/main/res/raw/sing_box_extended_license.txt"
# Tags our host verifier adds on top of what the upstream builder knows.
HOST_ONLY_TAGS: frozenset[str] = frozenset()


class ResolverError(RuntimeError):
    """A user-actionable reason to refuse a core pin."""


def read_properties(root: Path = ROOT) -> tuple[str, dict[str, str]]:
    text = (root / "core.properties").read_text(encoding="utf-8")
    values = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
    return text, values


def version_key(tag: str) -> tuple[int, ...] | None:
    match = TAG_PATTERN.fullmatch(tag)
    return tuple(int(part) for part in match.groups()) if match else None


def latest_stable_tag(timeout: float = 30.0) -> str:
    """Return GitHub's latest non-draft, non-prerelease extended release."""

    url = f"https://api.github.com/repos/{GITHUB_REPOSITORY}/releases/latest"
    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with urlopen(Request(url, headers=headers), timeout=timeout) as response:
            release = json.loads(response.read(8 * 1024 * 1024).decode("utf-8"))
    except (HTTPError, URLError, TimeoutError, OSError, ValueError) as error:
        raise ResolverError(f"GitHub latest release lookup failed: {error}") from error
    tag = str(release.get("tag_name") or "")
    # Missing flags are not treated as safe defaults.
    if release.get("draft") is not False or release.get("prerelease") is not False:
        raise ResolverError(f"latest release {tag!r} is not marked stable")
    if version_key(tag) is None:
        raise ResolverError(f"unexpected sing-box-extended tag format: {tag!r}")
    return tag


def tag_commit(repository: str, tag: str) -> str:
    """Resolve a tag to its commit, peeling annotated tags."""

    output = subprocess.run(
        ["git", "ls-remote", repository, f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"],
        check=True, capture_output=True, text=True, timeout=120,
    ).stdout
    refs = dict(reversed(line.split("\t", 1)) for line in output.splitlines() if "\t" in line)
    commit = refs.get(f"refs/tags/{tag}^{{}}") or refs.get(f"refs/tags/{tag}", "")
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ResolverError(f"tag {tag} was not found in {repository}")
    return commit


def _git(checkout: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", "-C", str(checkout), *arguments],
        check=True, capture_output=True, text=True, timeout=600,
    ).stdout.strip()


def _libbox_tags(builder_source: str) -> set[str]:
    tags: set[str] = set()
    for line in builder_source.splitlines():
        if re.match(r"\s*sharedTags = append\(sharedTags,", line):
            tags.update(re.findall(r'"([^"]+)"', line))
    if not tags:
        raise ResolverError("upstream build_libbox no longer declares sharedTags")
    return tags


def _script_tags(script: Path, variable: str) -> set[str]:
    match = re.search(rf'(?m)^{variable}="([^"]+)"', script.read_text(encoding="utf-8"))
    if match is None:
        raise ResolverError(f"{script.name} no longer defines {variable}")
    return set(match.group(1).split(","))


def patch_series(root: Path = ROOT) -> list[tuple[str, bytes]]:
    _, properties = read_properties(root)
    manifest = root / properties["CORE_PATCH_FILE"]
    return [
        (relative, (root / relative).read_bytes())
        for _, relative in (line.split() for line in manifest.read_text(encoding="utf-8").splitlines())
    ]


def validate_candidate(
    tag: str,
    commit: str,
    *,
    root: Path = ROOT,
    patches: Iterable[tuple[str, bytes]] | None = None,
) -> None:
    """Refuse a core unless the Android build contract still holds for it.

    ``patches`` lets a caller validate patch text it is about to write (the
    coordinated release retargets the Amnezia patch in memory first).
    """

    if version_key(tag) is None:
        raise ResolverError(f"unexpected sing-box-extended tag format: {tag!r}")
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ResolverError("core commit must be a full lowercase SHA")
    _, properties = read_properties(root)
    repository = properties["CORE_REPOSITORY"]
    if tag_commit(repository, tag) != commit:
        raise ResolverError(f"{tag} does not point to {commit}")
    series = list(patches) if patches is not None else patch_series(root)
    with tempfile.TemporaryDirectory(prefix="core-candidate-") as temporary:
        checkout = Path(temporary)
        _git(checkout, "init", "-q")
        _git(checkout, "fetch", "-q", "--depth=1", repository, commit)
        _git(checkout, "checkout", "-q", "--detach", "FETCH_HEAD")

        problems: list[str] = []
        if (checkout / "LICENSE").read_bytes() != (root / PACKAGED_LICENSE).read_bytes():
            problems.append(f"upstream LICENSE changed; review it and update {PACKAGED_LICENSE}")

        go_mod = (checkout / "go.mod").read_text(encoding="utf-8")
        go_directive = re.search(r"(?m)^go (\S+)$", go_mod)
        if go_directive is None or _go_version(go_directive.group(1)) > _go_version(properties["GO_VERSION"]):
            problems.append(f"upstream needs Go {go_directive and go_directive.group(1)}, pinned GO_VERSION is {properties['GO_VERSION']}")
        gomobile = re.search(r"(?m)^\s*github\.com/sagernet/gomobile (\S+)", go_mod)
        if gomobile is None or gomobile.group(1) != properties["GOMOBILE_VERSION"]:
            problems.append(f"upstream gomobile is {gomobile and gomobile.group(1)}, pinned GOMOBILE_VERSION is {properties['GOMOBILE_VERSION']}")
        sing_replace = re.search(r"(?m)^replace github\.com/sagernet/sing => github\.com/shtorm-7/sing (\S+)$", go_mod)
        udp_versions = json.loads((root / "core-patches/sing-udp.json").read_text(encoding="utf-8"))["versions"]
        if sing_replace is None or sing_replace.group(1) not in udp_versions:
            problems.append(f"no sing UDP patch for sing {sing_replace and sing_replace.group(1)} (core-patches/sing-udp.json)")

        libbox_tags = _libbox_tags((checkout / "cmd/internal/build_libbox/main.go").read_text(encoding="utf-8"))
        symbol_tags = _script_tags(root / "scripts/build-native-symbols.sh", "LIBBOX_TAGS")
        if symbol_tags != libbox_tags:
            problems.append(
                "build-native-symbols.sh LIBBOX_TAGS differ from upstream build_libbox: "
                f"missing {sorted(libbox_tags - symbol_tags)}, stale {sorted(symbol_tags - libbox_tags)}"
            )
        host_tags = _script_tags(root / "scripts/build-core.sh", "CORE_TAGS")
        if stale := sorted(host_tags - libbox_tags - HOST_ONLY_TAGS):
            problems.append(f"build-core.sh CORE_TAGS use tags upstream no longer builds: {stale}")

        for name, text in series:
            applied = subprocess.run(
                ["git", "-C", str(checkout), "apply", "--whitespace=nowarn", "-"],
                input=text, capture_output=True, timeout=120,
            )
            if applied.returncode:
                detail = applied.stderr.decode("utf-8", "replace").strip().splitlines()[:3]
                problems.append(f"{name} does not apply: " + " | ".join(detail))
                break

        if problems:
            raise ResolverError(
                f"sing-box-extended {tag} cannot be pinned for Android; port required:\n- "
                + "\n- ".join(problems)
            )


def _go_version(value: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", value)[:3])


def pin_changes(properties_text: str, tag: str, commit: str) -> str:
    """Return core.properties text with the core tag and commit replaced."""

    for key, value in (("CORE_TAG", tag), ("CORE_COMMIT", commit)):
        properties_text, count = re.subn(rf"(?m)^{key}=.*$", f"{key}={value}", properties_text)
        if count != 1:
            raise ResolverError(f"missing or repeated {key} in core.properties")
    return properties_text


def write_properties(root: Path, text: str) -> None:
    path = root / "core.properties"
    fd, temporary = tempfile.mkstemp(prefix=".core.properties.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--require-current", action="store_true", help="with --check: fail if a newer stable exists")
    parser.add_argument("--tag", help="with --write: pin this tag instead of the latest stable")
    parser.add_argument("--commit", help="with --tag: expected commit of the tag")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    text, properties = read_properties(root)
    current = properties["CORE_TAG"]
    try:
        if args.check:
            latest = latest_stable_tag()
            if version_key(latest) > version_key(current):
                print(f"sing-box-extended {latest} is available; Android pins {current}")
                return 3 if args.require_current else 0
            print(f"Android core {current} is current")
            return 0
        tag = args.tag or latest_stable_tag()
        commit = args.commit or tag_commit(properties["CORE_REPOSITORY"], tag)
        if tag == current and commit == properties["CORE_COMMIT"]:
            print(f"Android core {current} is already pinned")
            return 0
        validate_candidate(tag, commit, root=root)
        write_properties(root, pin_changes(text, tag, commit))
        print(f"Android core pinned: {current} -> {tag} ({commit}); libbox rebuilds on the next build")
        return 0
    except (ResolverError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 4


if __name__ == "__main__":
    sys.exit(main())
