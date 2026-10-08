#!/usr/bin/env python3
"""Перевести встроенную Hysteria на новую официальную версию.

Автоматизирует docs/CORE_UPDATE.md. Hysteria вшита в libbox патчем
core-patches/0003, а следующие патчи трогают соседние строки go.mod, поэтому
новая версия — это перегенерация серии, а не правка одной строки.

Скрипт работает во временной копии исходников ядра и пишет в репозиторий
только после того, как итог прошёл все проверки:

- каждый текущий патч байт в байт воспроизводится из цепочки коммитов, то есть
  новые патчи отличаются от прежних только самим обновлением;
- `go mod tidy -diff` не требует изменений go.mod;
- пакеты, которые затрагивают патчи, компилируются с боевыми тегами сборки.

Любое расхождение — отказ без записи. Сборку libbox.aar выполняет preBuild-хук
Gradle (scripts/ensure-libbox.sh), когда видит новые пины.

    scripts/update_hysteria_core.py --tag app/v2.13.0            # проверить
    scripts/update_hysteria_core.py --tag app/v2.13.0 --write    # записать
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HYSTERIA_MODULES = ("github.com/apernet/hysteria/core/v2", "github.com/apernet/hysteria/extras/v2")
HYSTERIA_PATCH_INDEX = 3
COMPILE_PACKAGES = (
    "./protocol/hysteria2",
    "./protocol/xraycore",
    "./parser/link",
    "./route/rule",
    "./transport/wireguard",
)
VERSION_FILES = (
    "docs/THIRD_PARTY_NOTICES.md",
    "app/src/main/res/raw/third_party_notices.txt",
    "docs/IMPORT_FORMATS.md",
)


class UpdateError(RuntimeError):
    """Обновление нельзя выполнить автоматически; репозиторий не изменён."""


def read_properties(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)


def sha256_text(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Workspace:
    def __init__(self, source: Path, go: str, env: dict[str, str]) -> None:
        self.source = source
        self.go = go
        self.env = env

    def run(self, *args: str, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(
            list(args), cwd=self.source, env=self.env, check=check, text=True,
            stdout=subprocess.PIPE if capture else None, stderr=subprocess.PIPE if capture else None,
        )

    def git(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        return self.run("git", "-c", "user.name=core-update", "-c", "user.email=core-update@localhost",
                        "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", *args, check=check)

    def diff(self, older: str, newer: str) -> bytes:
        return subprocess.run(["git", "diff", "--abbrev=8", older, newer], cwd=self.source, env=self.env,
                              check=True, stdout=subprocess.PIPE).stdout

    def tidy_go_mod_lines(self) -> list[str]:
        """Строки, которые `go mod tidy` поменял бы в go.mod (без go.sum)."""

        result = self.run(self.go, "mod", "tidy", "-diff", check=False)
        if result.returncode not in (0, 1):
            raise UpdateError(f"go mod tidy -diff: {result.stderr.strip()[-400:]}")
        lines: list[str] = []
        in_go_mod = False
        for line in result.stdout.splitlines():
            if line.startswith("diff "):
                in_go_mod = line.rstrip().endswith("go.mod")
                continue
            if in_go_mod and line[:1] in "+-" and not line.startswith(("+++", "---")):
                lines.append(line)
        return lines


def require_lines(go_mod: str) -> dict[str, str]:
    """module -> полная строка require (с отступом и комментарием)."""

    found: dict[str, str] = {}
    for line in go_mod.splitlines():
        match = re.fullmatch(r"\t(\S+) (v\S+)( // indirect)?", line)
        if match:
            found[match.group(1)] = line
    return found


def resolve_go_mod_conflict(text: str, upgrades: dict[str, str]) -> str:
    """Конфликт в go.mod: сторона патча, но с версиями обновлённых модулей.

    Следующий патч серии написан поверх прежней Hysteria, поэтому его сторона
    верна во всём, кроме версий, которые только что поднял `go get`.
    """

    def pick(match: re.Match) -> str:
        theirs = match.group(2)
        for old, new in upgrades.items():
            theirs = theirs.replace(old, new)
        return theirs

    resolved, count = re.subn(r"<<<<<<< [^\n]*\n(.*?)=======\n(.*?)>>>>>>> [^\n]*\n", pick, text, flags=re.S)
    if count == 0 or "<<<<<<<" in resolved or ">>>>>>>" in resolved:
        raise UpdateError("go.mod: конфликт не удалось разрешить по правилу обновления версий")
    return resolved


def resolve_go_sum_conflict(text: str, stale: list[str]) -> str:
    """Конфликт в go.sum: объединение сторон без хешей прежних версий."""

    lines = sorted({line for line in text.splitlines() if line and not line.startswith(("<<<<<<<", "=======", ">>>>>>>"))})
    return "\n".join(line for line in lines if not any(line.startswith(prefix) for prefix in stale)) + "\n"


def build_tags(root: Path) -> str:
    match = re.search(r'^CORE_TAGS="([^"]+)"', (root / "scripts/build-core.sh").read_text(encoding="utf-8"), re.M)
    if not match:
        raise UpdateError("scripts/build-core.sh: не найден CORE_TAGS")
    # Те же теги, что у тестов сборки: без обхода проверки linkname.
    return match.group(1).removesuffix(",badlinkname,tfogo_checklinkname0")


def obtain_source(root: Path, properties: dict[str, str], destination: Path) -> None:
    cached = root / "core-build/source"
    if (cached / ".git").exists():
        head = subprocess.run(["git", "-C", str(cached), "rev-parse", "HEAD"], text=True, capture_output=True)
        dirty = subprocess.run(["git", "-C", str(cached), "status", "--porcelain"], text=True, capture_output=True)
        if head.stdout.strip() == properties["CORE_COMMIT"] and not dirty.stdout.strip():
            # Не `git clone --shared`: core-build/source — неглубокий клон.
            shutil.copytree(cached, destination, symlinks=True)
            return
    destination.mkdir(parents=True)
    for command in (
        ["git", "init", "-q"],
        ["git", "remote", "add", "origin", properties["CORE_REPOSITORY"]],
        ["git", "fetch", "-q", "--depth=1", "origin", properties["CORE_COMMIT"]],
        ["git", "checkout", "-q", "FETCH_HEAD"],
    ):
        subprocess.run(command, cwd=destination, check=True)


def update(root: Path, tag: str, write: bool, go: str) -> dict:
    if not re.fullmatch(r"app/v\d+\.\d+\.\d+", tag):
        raise UpdateError(f"Ожидается тег вида app/vX.Y.Z, получено: {tag}")
    version = tag.removeprefix("app/")
    properties_path = root / "core.properties"
    properties = read_properties(properties_path)
    previous_tag = properties["HYSTERIA_CORE_TAG"]
    if previous_tag == tag:
        return {"changed": False, "tag": tag, "commit": properties["HYSTERIA_CORE_COMMIT"]}

    manifest_path = root / properties["CORE_PATCH_FILE"]
    series = [line.split() for line in manifest_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    for digest, relative in series:
        if sha256_text((root / relative).read_bytes()) != digest:
            raise UpdateError(f"Патч не совпадает с series.sha256: {relative}")
    if len(series) < HYSTERIA_PATCH_INDEX or "hysteria" not in series[HYSTERIA_PATCH_INDEX - 1][1]:
        raise UpdateError("Патч Hysteria больше не третий в серии: обновите скрипт под новую серию")

    expected_go = "go" + properties["GO_VERSION"]
    actual_go = subprocess.run([go, "env", "GOVERSION"], text=True, capture_output=True, check=True).stdout.strip()
    if actual_go != expected_go:
        raise UpdateError(f"Нужен {expected_go} (GO_VERSION), найден {actual_go}")

    with tempfile.TemporaryDirectory(prefix="hysteria-core-update-") as temporary:
        source = Path(temporary) / "source"
        obtain_source(root, properties, source)
        env = {**os.environ, "GOTOOLCHAIN": "local", "GOFLAGS": "-mod=mod", "GIT_EDITOR": "true"}
        work = Workspace(source, go, env)

        # Цепочка коммитов p0..pN и доказательство, что настройки диффа дают
        # прежние патчи байт в байт.
        work.git("tag", "-f", "p0")
        for index, (_, relative) in enumerate(series, start=1):
            work.git("apply", "--whitespace=nowarn", str(root / relative))
            work.git("add", "-A")
            work.git("commit", "-q", "-m", f"p{index}")
            work.git("tag", "-f", f"p{index}")
            if work.diff(f"p{index - 1}", f"p{index}") != (root / relative).read_bytes():
                raise UpdateError(f"Патч не воспроизводится из цепочки коммитов: {relative}")

        base = f"p{HYSTERIA_PATCH_INDEX}"
        work.git("checkout", "-q", base)
        baseline_tidy = set(work.tidy_go_mod_lines())
        before = require_lines((source / "go.mod").read_text(encoding="utf-8"))
        fetched = work.run(go, "get", *(f"{module}@{version}" for module in HYSTERIA_MODULES), check=False)
        if fetched.returncode != 0:
            raise UpdateError(f"go get Hysteria {version}: {fetched.stderr.strip()[-600:]}")
        after = require_lines((source / "go.mod").read_text(encoding="utf-8"))
        if set(before) != set(after):
            raise UpdateError("go get изменил состав модулей go.mod: нужен ручной разбор по docs/CORE_UPDATE.md")
        upgrades = {before[module].strip(): after[module].strip() for module in before if before[module] != after[module]}
        if not all(module in " ".join(upgrades) for module in HYSTERIA_MODULES):
            raise UpdateError("go get не поднял модули Hysteria")
        new_tidy = [line for line in work.tidy_go_mod_lines() if line not in baseline_tidy]
        if new_tidy:
            raise UpdateError("go mod tidy требует новых строк go.mod: " + "; ".join(new_tidy[:6]))

        # Из go.sum уходят только хеши прежних версий обновлённых модулей.
        stale = [re.sub(r" // indirect$", "", old) + suffix for old in upgrades for suffix in (" ", "/go.mod ")]
        go_sum = source / "go.sum"
        go_sum.write_text(
            "".join(line for line in go_sum.read_text(encoding="utf-8").splitlines(keepends=True)
                    if not any(line.startswith(prefix) for prefix in stale)),
            encoding="utf-8",
        )
        work.git("add", "-A")
        work.git("commit", "-q", "-m", f"n{HYSTERIA_PATCH_INDEX}")
        work.git("tag", "-f", f"n{HYSTERIA_PATCH_INDEX}")

        for index in range(HYSTERIA_PATCH_INDEX + 1, len(series) + 1):
            picked = work.git("cherry-pick", f"p{index}", check=False)
            if picked.returncode != 0:
                conflicted = work.git("diff", "--name-only", "--diff-filter=U").stdout.split()
                unexpected = [name for name in conflicted if name not in ("go.mod", "go.sum")]
                if unexpected or not conflicted:
                    raise UpdateError(f"Патч {series[index - 1][1]} конфликтует вне go.mod/go.sum: {unexpected}")
                for name in conflicted:
                    path = source / name
                    text = path.read_text(encoding="utf-8")
                    path.write_text(
                        resolve_go_mod_conflict(text, upgrades) if name == "go.mod" else resolve_go_sum_conflict(text, stale),
                        encoding="utf-8",
                    )
                    work.git("add", name)
                work.git("cherry-pick", "--continue")
            work.git("tag", "-f", f"n{index}")

        final_mod = (source / "go.mod").read_text(encoding="utf-8")
        final_sum = go_sum.read_text(encoding="utf-8")
        for old, new in upgrades.items():
            if old in final_mod or new not in final_mod:
                raise UpdateError(f"В итоговом go.mod осталась прежняя версия: {old}")
        if any(line.startswith(prefix) for line in final_sum.splitlines() for prefix in stale):
            raise UpdateError("В итоговом go.sum остались хеши прежних версий")

        patches: dict[str, bytes] = {}
        for index, (_, relative) in enumerate(series, start=1):
            if index < HYSTERIA_PATCH_INDEX:
                patches[relative] = (root / relative).read_bytes()
                continue
            older = f"p{index - 1}" if index == HYSTERIA_PATCH_INDEX else f"n{index - 1}"
            patches[relative] = work.diff(older, f"n{index}")

        # Итоговое дерево должно собираться: подготовка Xray, tidy и компиляция
        # затронутых патчами пакетов с боевыми тегами.
        prepare = source / "protocol/xraycore/prepare.sh"
        if prepare.exists():
            work.run("bash", str(prepare), properties["XRAY_CORE_MODULE"], properties["XRAY_CORE_COMMIT"])
        compile_env = {**env, "GOFLAGS": ""}
        tidy = Workspace(source, go, compile_env).tidy_go_mod_lines()
        if tidy:
            raise UpdateError("Итоговый go.mod не приведён (go mod tidy -diff): " + "; ".join(tidy[:6]))
        compiled = subprocess.run(
            [go, "test", "-count=1", "-run", "^$", "-tags", build_tags(root), *COMPILE_PACKAGES],
            cwd=source, env=compile_env, text=True, capture_output=True,
        )
        if compiled.returncode != 0:
            raise UpdateError("Ядро не компилируется с новой Hysteria:\n" + (compiled.stdout + compiled.stderr)[-1500:])

        origin = json.loads(work.run(go, "mod", "download", "-json", f"{HYSTERIA_MODULES[0]}@{version}").stdout)["Origin"]
        commit = origin["Hash"]
        if not re.fullmatch(r"[0-9a-f]{40}", commit) or not str(origin.get("Ref", "")).endswith(f"/{version}"):
            raise UpdateError(f"Не удалось подтвердить коммит Hysteria {version}: {origin}")

    manifest = "".join(f"{sha256_text(patches[relative])}  {relative}\n" for _, relative in series)
    new_properties = properties_path.read_text(encoding="utf-8")
    for key, value in (
        ("HYSTERIA_CORE_TAG", tag),
        ("HYSTERIA_CORE_COMMIT", commit),
        ("CORE_PATCH_SHA256", sha256_text(manifest.encode())),
    ):
        new_properties, count = re.subn(rf"(?m)^{key}=.*$", f"{key}={value}", new_properties)
        if count != 1:
            raise UpdateError(f"core.properties: ожидался один ключ {key}")

    previous_version = previous_tag.removeprefix("app/")
    documents: dict[Path, str] = {}
    for relative in VERSION_FILES:
        path = root / relative
        text = path.read_text(encoding="utf-8")
        updated = text.replace(previous_tag, tag).replace(f"@{previous_version}", f"@{version}")
        updated = updated.replace(properties["HYSTERIA_CORE_COMMIT"], commit)
        if previous_tag in text and updated == text:
            raise UpdateError(f"Не удалось обновить версию в {relative}")
        documents[path] = updated

    result = {
        "changed": True, "previous": previous_tag, "tag": tag, "commit": commit,
        "patches": [relative for _, relative in series if patches[relative] != (root / relative).read_bytes()],
        "upgrades": upgrades,
    }
    if write:
        for relative, data in patches.items():
            (root / relative).write_bytes(data)
        manifest_path.write_text(manifest, encoding="utf-8")
        properties_path.write_text(new_properties, encoding="utf-8")
        for path, text in documents.items():
            path.write_text(text, encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tag", required=True, help="официальный тег Hysteria, например app/v2.13.0")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--go", default="go")
    parser.add_argument("--write", action="store_true", help="записать патчи и пины; без флага — только проверка")
    args = parser.parse_args()
    try:
        result = update(args.root.resolve(), args.tag, args.write, args.go)
    except UpdateError as error:
        print(f"update_hysteria_core: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
