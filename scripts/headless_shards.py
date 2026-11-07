#!/usr/bin/env python3
"""Run the headless GameTests in parallel shards, one reusable worktree per shard.

Called by `setup-minecraft-headless.sh --parallel N`. The scenes selected by --all, --group or
--filter are read from the GameTest sources and split by batch, never inside one: scenes of a
batch run side by side in one server and may rely on it. Every shard runs the committed HEAD in
`.worktrees/headless-shard-K`, so two servers never share `neoforge/build/gametest/server/`.
The shard logs, reports and one summary with every failed scene land in the main checkout.
"""

import argparse
import re
import shutil
import subprocess
import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCENES = ROOT / "neoforge/src/gameTest/kotlin"
GAMETEST = re.compile(r"@GameTest\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*fun\s+(\w+)")
# A scene whose timeout is a constant weighs as a long one; it only steers the split.
DEFAULT_WEIGHT = 1200
# Vanilla runs scenes without a batch together in this one.
DEFAULT_BATCH = "defaultBatch"
# What the shard worktree needs from the main checkout to bootstrap offline.
SHARED = ["SuperbWarfare", "build/headless/downloads", "build/headless/tools"]


def classes_in(source: str, function: str) -> list[str]:
    body = source[source.index(function):]
    body = body[: body.index(")\n")]
    return re.findall(r"(\w+GameTests)::class\.java", body)


def groups() -> dict[str, list[str]]:
    source = (SCENES / "com/sbwnpc/squad/gametest/GameTestGroups.kt").read_text()
    found = {}
    for name, body in re.findall(r'"([a-z-]+)" to listOf\((.*?)\)', source, re.S):
        found[name] = re.findall(r"(\w+GameTests)::class\.java", body)
    return found


def scenes(selected: set[str]) -> dict[str, tuple[str, int]]:
    """Every scene of [selected] classes: name to (batch, timeout)."""
    found = {}
    for path in SCENES.rglob("*GameTests.kt"):
        if path.stem not in selected:
            continue
        for args, name in GAMETEST.findall(path.read_text()):
            batch = re.search(r'batch = "([^"]+)"', args)
            timeout = re.search(r"timeoutTicks = (\d+)", args)
            found[name] = (batch.group(1) if batch else DEFAULT_BATCH, int(timeout.group(1)) if timeout else DEFAULT_WEIGHT)
    return found


def select(args) -> dict[str, tuple[str, int]]:
    registration = (SCENES / "com/sbwnpc/squad/gametest/GameTestRegistration.kt").read_text()
    registered = classes_in(registration, "fun sceneClasses")
    known = groups()
    if args.group:
        unknown = [g for g in args.group.split(",") if g not in known]
        if unknown:
            sys.exit(f"Unknown GameTest group: {unknown}; available: {list(known)}")
        chosen = {c for g in args.group.split(",") for c in known[g]}
    else:
        chosen = set(registered)
    found = scenes(chosen)
    if args.filter:
        names = args.filter.split(",")
        unknown = [n for n in names if n not in found]
        if unknown:
            sys.exit(f"Unknown GameTest: {unknown}")
        found = {n: found[n] for n in names}
    if not found:
        sys.exit("No GameTests selected")
    return found


def split(found: dict[str, tuple[str, int]], count: int) -> list[list[str]]:
    """Whole batches, heaviest first, each into the lightest shard. A batch weighs its longest scene."""
    batches: dict[str, list[str]] = {}
    for name, (batch, _) in found.items():
        batches.setdefault(batch, []).append(name)
    weight = {b: max(found[n][1] for n in names) for b, names in batches.items()}
    shards: list[list[str]] = [[] for _ in range(count)]
    load = [0] * count
    for batch in sorted(batches, key=lambda b: (-weight[b], b)):
        lightest = load.index(min(load))
        shards[lightest] += batches[batch]
        load[lightest] += weight[batch]
    return [s for s in shards if s]


def git(*args, cwd=ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def worktree(index: int, head: str) -> Path:
    tree = ROOT / ".worktrees" / f"headless-shard-{index}"
    if (tree / ".git").exists():
        git("checkout", "--quiet", "--detach", "--force", head, cwd=tree)
    else:
        git("worktree", "add", "--quiet", "--detach", str(tree), head)
    for shared in SHARED:
        source, link = ROOT / shared, tree / shared
        if source.exists() and not link.exists():
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(source)
    return tree


def outcome(log: Path) -> tuple[bool, list[str]]:
    """Whether the server finished its scenes, and the failed ones (the log prints them in lower case)."""
    text = log.read_text(errors="replace") if log.exists() else ""
    complete = "GAME TESTS COMPLETE" in text
    failed = []
    marker = text.rfind("required tests failed")
    if marker >= 0:
        for line in text[marker:].splitlines()[1:]:
            item = re.search(r"\]:\s+- (\S+)$", line)
            if not item:
                break
            failed.append(item.group(1))
    return complete, failed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--shards", type=int, required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--group")
    parser.add_argument("--filter")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    if git("status", "--porcelain", "--untracked-files=no"):
        sys.exit("Shards run the committed HEAD: commit or stash the changes first")
    found = select(args)
    shards = split(found, args.shards)
    head = git("rev-parse", "HEAD")
    logs = ROOT / "build/headless/logs"
    reports = ROOT / "neoforge/build/reports/gameTest" / args.report
    logs.mkdir(parents=True, exist_ok=True)
    reports.mkdir(parents=True, exist_ok=True)
    print(f"{len(found)} scenes in {len(shards)} shards at {head[:7]}")
    runs = []
    for index, names in enumerate(shards, 1):
        tree = worktree(index, head)
        name = f"{args.report}-s{index}"
        command = ["bash", "setup-minecraft-headless.sh", "--filter", ",".join(names), "--report", name]
        if args.offline:
            command.append("--offline")
        print(f"  shard {index}: {len(names)} scenes, log {logs / (name + '.log')}")
        runs.append((index, tree, name, names, command))

    codes = {}

    def run(index, tree, name, names, command):
        with open(logs / f"{name}.runner.log", "w") as out:
            codes[index] = subprocess.run(command, cwd=tree, stdout=out, stderr=subprocess.STDOUT).returncode

    threads = [threading.Thread(target=run, args=r) for r in runs]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    lower = {n.lower(): n for n in found}
    summary, broken = [], False
    for index, tree, name, names, _ in runs:
        complete, failed = outcome(tree / "build/headless/logs" / f"{name}.log")
        log = tree / "build/headless/logs" / f"{name}.log"
        if log.exists():
            shutil.copy2(log, logs / f"{name}.log")
        produced = tree / "neoforge/build/reports/gameTest" / name
        if produced.exists():
            shutil.copytree(produced, reports / f"shard-{index}", dirs_exist_ok=True)
        if not complete:
            broken = True
            summary.append(f"shard {index}: server did not finish (exit {codes[index]}); see {logs / (name + '.log')}")
        for item in failed:
            summary.append(f"FAILED {lower.get(item, item)} (shard {index})")
    failures = sum(1 for line in summary if line.startswith("FAILED"))
    verdict = "incomplete: " if broken else ""
    summary.insert(0, f"{verdict}{len(found) - failures}/{len(found)} scenes passed in {len(runs)} shards at {head[:7]}")
    (reports / "summary.txt").write_text("\n".join(summary) + "\n")
    print("\n".join(summary))
    print(f"Reports: {reports}")
    return 1 if broken or len(summary) > 1 else 0


if __name__ == "__main__":
    sys.exit(main())
