#!/usr/bin/env python3
"""Fake Forgejo for publisher tests: a `curl` stand-in plus a tag-sync hook.

Invoked as `curl …` (through a PATH shim) it serves the release API the
publishers use from a JSON state file. Invoked as `fake-forgejo.py tag-sync
<tag>` (from the bare origin's post-receive hook) it models Forgejo's
asynchronous tag processing at its worst: the sync is queued and fires on the
first API call at which a draft carrying the tag exists, publishing it. It
records how many assets that draft held — exactly what decides whether a
partial release could go public (Android v0.4.4 went out with 3 of 8).
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import sys
from urllib.parse import parse_qs, unquote, urlsplit

STATE_DIR = Path(os.environ["FAKE_FORGEJO_DIR"])
STATE = STATE_DIR / "state.json"
BASE = os.environ.get("FAKE_FORGEJO_BASE", "https://forgejo.test")


def load() -> dict:
    return json.loads(STATE.read_text()) if STATE.exists() else {"next_id": 1, "releases": []}


def save(state: dict) -> None:
    STATE.write_text(json.dumps(state))


def log(line: str) -> None:
    with (STATE_DIR / "calls.log").open("a") as handle:
        handle.write(line + "\n")


def release_view(release: dict, repo: str) -> dict:
    assets = [
        {"name": name, "size": (STATE_DIR / "assets" / str(release["id"]) / name).stat().st_size,
         "browser_download_url": f"{BASE}/{repo}/releases/download/{release['tag_name']}/{name}"}
        for name in release["assets"]
    ]
    return {"id": release["id"], "tag_name": release["tag_name"], "draft": release["draft"],
            "prerelease": release["prerelease"], "html_url": f"{BASE}/{repo}/releases/tag/{release['tag_name']}",
            "assets": assets}


def tag_sync(tag: str) -> None:
    state = load()
    state.setdefault("pending_tags", []).append(tag)
    save(state)
    run_pending_syncs()


def run_pending_syncs() -> None:
    state = load()
    pending = []
    for tag in state.get("pending_tags", []):
        drafts = [r for r in state["releases"] if r["tag_name"] == tag and r["draft"]]
        if not drafts:
            pending.append(tag)
            continue
        for release in drafts:
            log(f"tag-sync {tag} assets={len(release['assets'])} draft=true")
            release["draft"] = False
    state["pending_tags"] = pending
    save(state)


def curl(argv: list[str]) -> int:
    method, output, write_out, data, attachment, fail, url = "GET", None, None, None, None, False, None
    args = iter(argv)
    for arg in args:
        if arg in ("--request", "-X"):
            method = next(args)
        elif arg in ("--output", "-o"):
            output = next(args)
        elif arg in ("--write-out", "-w"):
            write_out = next(args)
        elif arg in ("--data", "--data-binary", "-d"):
            data = next(args)
        elif arg == "-F":
            attachment = next(args).split("@", 1)[1]
        elif arg in ("-H", "--connect-timeout", "--max-time", "--proto", "--max-redirs"):
            next(args)
        elif arg == "--fail":
            fail = True
        elif not arg.startswith("-"):
            url = arg
    if data and data.startswith("@"):
        attachment, data = data[1:], None
    if method == "GET" and (data or attachment):
        method = "POST"
    parts = urlsplit(url)
    path, query = unquote(parts.path), parse_qs(parts.query)
    state = load()
    status, body = 404, b'{"message":"not found"}'

    if "/releases/download/" in path:
        repo_and_rest = path.lstrip("/").split("/releases/download/", 1)
        tag, name = repo_and_rest[1].split("/", 1)
        for release in state["releases"]:
            if release["tag_name"] == tag and name in release["assets"]:
                status, body = 200, (STATE_DIR / "assets" / str(release["id"]) / name).read_bytes()
    else:
        repo = path.split("/api/v1/repos/", 1)[1].split("/releases", 1)[0]
        tail = path.split("/releases", 1)[1]
        if tail.startswith("/tags/") and method == "GET":
            tag = tail[len("/tags/"):]
            for release in state["releases"]:
                if release["tag_name"] == tag:
                    status, body = 200, json.dumps(release_view(release, repo)).encode()
        elif tail == "/latest":
            published = [r for r in state["releases"] if not r["draft"] and not r["prerelease"]]
            if published:
                status, body = 200, json.dumps(release_view(max(published, key=lambda r: r["id"]), repo)).encode()
        elif tail == "" and method == "POST":
            payload = json.loads(data)
            release = {"id": state["next_id"], "tag_name": payload["tag_name"], "draft": payload["draft"],
                       "prerelease": payload["prerelease"], "assets": []}
            state["next_id"] += 1
            state["releases"].append(release)
            (STATE_DIR / "assets" / str(release["id"])).mkdir(parents=True, exist_ok=True)
            save(state)
            log(f"create {release['tag_name']}")
            status, body = 201, json.dumps(release_view(release, repo)).encode()
        elif tail.endswith("/assets") and method == "POST":
            release_id = int(tail.split("/")[1])
            name = query["name"][0]
            release = next(r for r in state["releases"] if r["id"] == release_id)
            shutil.copyfile(attachment, STATE_DIR / "assets" / str(release_id) / name)
            if name not in release["assets"]:
                release["assets"].append(name)
            save(state)
            log(f"upload {name}")
            status, body = 201, b"{}"
        elif method == "PATCH":
            release_id = int(tail.lstrip("/"))
            release = next(r for r in state["releases"] if r["id"] == release_id)
            payload = json.loads(data)
            release["draft"], release["prerelease"] = payload["draft"], payload["prerelease"]
            save(state)
            log(f"publish {release['tag_name']} assets={len(release['assets'])}")
            status, body = 200, json.dumps(release_view(release, repo)).encode()

    run_pending_syncs()
    if output:
        Path(output).write_bytes(body)
    elif not (fail and status >= 400):
        sys.stdout.buffer.write(body)
    if write_out:
        sys.stdout.write(write_out.replace("%{http_code}", str(status)))
    return 22 if fail and status >= 400 else 0


if __name__ == "__main__":
    if sys.argv[1:2] == ["tag-sync"]:
        tag_sync(sys.argv[2])
        sys.exit(0)
    sys.exit(curl(sys.argv[1:]))
