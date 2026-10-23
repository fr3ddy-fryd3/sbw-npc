#!/usr/bin/env python3
"""Build a local test modpack for one loader: the pinned SuperbWarfare, the addon's dependencies and quality-of-life mods.

    scripts/localpack.py fabric --addon fabric/build/libs/sbwnpc-<version>-mc1.21.1.jar
    scripts/localpack.py forge  --mrpack
    scripts/localpack.py neoforge --no-qol

Mods come from Modrinth, named in scripts/localpack/<loader>.json. SuperbWarfare is not looked up: it is the exact
version pinned in gradle/sbw/<loader>.properties, checked against the SHA-512 recorded there. Required dependencies
of every mod are resolved and downloaded too. Downloads are cached by SHA-512 and checked on every use.

The result is a mods folder (default build/localpack/<loader>/mods) to copy into a launcher instance, and with --mrpack
a .mrpack file that Prism Launcher or the Modrinth app imports, installing the right Minecraft and loader.
"""

import argparse
import hashlib
import json
import shutil
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = "https://api.modrinth.com/v2"
USER_AGENT = "fr3ddy-fryd3/sbw-npc localpack (github.com/fr3ddy-fryd3/sbw-npc)"
MODRINTH_LOADERS = {"fabric": "fabric", "forge": "forge", "neoforge": "neoforge"}


def get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def api(path: str, **query: str):
    suffix = "?" + urllib.parse.urlencode(query) if query else ""
    return json.loads(get(API + path + suffix))


def properties(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def loader_version(loader: str) -> str:
    """The loader version the addon is built against, as a launcher expects it."""
    if loader == "fabric":
        return properties(ROOT / "gradle/fabric.properties")["fabric_loader_version"]
    if loader == "forge":
        return properties(ROOT / "gradle/forge.properties")["forge_version"].split("-", 1)[1]
    return properties(ROOT / "gradle.properties")["neo_version"]


def mrpack_loader_key(loader: str) -> str:
    return {"fabric": "fabric-loader", "forge": "forge", "neoforge": "neoforge"}[loader]


def newest_version(project: str, loader: str, minecraft: str) -> dict:
    versions = api(
        f"/project/{project}/version",
        loaders=json.dumps([MODRINTH_LOADERS[loader]]),
        game_versions=json.dumps([minecraft]),
    )
    if not versions:
        sys.exit(f"{project}: no version for {loader} on Minecraft {minecraft}")
    # Newest first; prefer a release over a beta or alpha, but take what exists.
    for kind in ("release", "beta", "alpha"):
        for version in versions:
            if version["version_type"] == kind:
                return version
    return versions[0]


def primary_file(version: dict) -> dict:
    return next((f for f in version["files"] if f.get("primary")), version["files"][0])


class Pack:
    def __init__(self, loader: str, minecraft: str):
        self.loader = loader
        self.minecraft = minecraft
        self.entries: dict[str, dict] = {}  # project id -> entry

    def add(self, version: dict, role: str, expected_sha512: str | None = None) -> None:
        project_id = version["project_id"]
        if project_id in self.entries:
            return
        file = primary_file(version)
        if expected_sha512 and file["hashes"]["sha512"] != expected_sha512:
            sys.exit(f"{file['filename']}: SHA-512 differs from the pin in gradle/sbw/{self.loader}.properties")
        project = api(f"/project/{project_id}")
        self.entries[project_id] = {
            "slug": project["slug"],
            "title": project["title"],
            "role": role,
            "version": version["version_number"],
            "file": file,
            "env": {"client": project["client_side"], "server": project["server_side"]},
        }
        for dependency in version["dependencies"]:
            if dependency["dependency_type"] != "required" or dependency["project_id"] is None:
                continue
            if dependency["project_id"] in self.entries:
                continue
            if dependency.get("version_id"):
                needed = api(f"/version/{dependency['version_id']}")
            else:
                needed = newest_version(dependency["project_id"], self.loader, self.minecraft)
            self.add(needed, "dependency")


def download(entry: dict, cache: Path) -> Path:
    file = entry["file"]
    digest = file["hashes"]["sha512"]
    target = cache / f"{digest}.jar"
    if not target.exists() or hashlib.sha512(target.read_bytes()).hexdigest() != digest:
        print(f"  downloading {file['filename']}")
        partial = target.with_suffix(".part")
        partial.write_bytes(get(file["url"]))
        if hashlib.sha512(partial.read_bytes()).hexdigest() != digest:
            partial.unlink()
            sys.exit(f"{file['filename']}: downloaded file does not match its SHA-512")
        partial.replace(target)
    return target


def find_addon(loader: str, given: Path | None) -> Path | None:
    if given:
        return given.resolve()
    libs = ROOT / loader / "build" / "libs"
    jars = sorted(libs.glob("sbwnpc-*.jar"), key=lambda p: p.stat().st_mtime) if libs.is_dir() else []
    return jars[-1] if jars else None


def check_addon(jar: Path) -> None:
    with zipfile.ZipFile(jar) as archive:
        if archive.testzip() is not None:
            sys.exit(f"{jar}: corrupt archive")


def write_mrpack(pack: Pack, entries: list[dict], addon: Path | None, out: Path, name: str) -> Path:
    files = []
    for entry in entries:
        file = entry["file"]
        files.append(
            {
                "path": f"mods/{file['filename']}",
                "hashes": {"sha1": file["hashes"]["sha1"], "sha512": file["hashes"]["sha512"]},
                "env": {"client": entry["env"]["client"], "server": entry["env"]["server"]},
                "downloads": [file["url"]],
                "fileSize": file["size"],
            }
        )
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": "local",
        "name": name,
        "summary": f"Local test pack for the {pack.loader} build of [SBW] NPC Squads",
        "files": files,
        "dependencies": {"minecraft": pack.minecraft, mrpack_loader_key(pack.loader): loader_version(pack.loader)},
    }
    target = out / f"{name}.mrpack"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("modrinth.index.json", json.dumps(index, indent=2))
        if addon:
            archive.write(addon, f"overrides/mods/{addon.name}")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("loader", choices=sorted(MODRINTH_LOADERS))
    parser.add_argument("--addon", type=Path, help="the built sbwnpc jar (default: newest in <loader>/build/libs)")
    parser.add_argument("--out", type=Path, help="output folder (default: build/localpack/<loader>)")
    parser.add_argument("--cache", type=Path, default=ROOT / "build" / "localpack" / "cache")
    parser.add_argument("--no-qol", action="store_true", help="only what the addon needs")
    parser.add_argument("--mrpack", action="store_true", help="also write an importable .mrpack")
    args = parser.parse_args()

    config = json.loads((ROOT / "scripts" / "localpack" / f"{args.loader}.json").read_text())
    pack = Pack(args.loader, config["minecraft"])
    out = (args.out or ROOT / "build" / "localpack" / args.loader).resolve()
    mods = out / "mods"

    sbw = properties(ROOT / "gradle" / "sbw" / f"{args.loader}.properties")
    _, project_id, version_id = sbw["coordinate"].split(":")
    print(f"SuperbWarfare {sbw['version']} (pinned)")
    pack.add(api(f"/version/{version_id}"), "superbwarfare", expected_sha512=sbw["sha512"])
    for slug in config["required"]:
        pack.add(newest_version(slug, args.loader, config["minecraft"]), "required")
    if not args.no_qol:
        for slug in config["qol"]:
            pack.add(newest_version(slug, args.loader, config["minecraft"]), "qol")

    args.cache.mkdir(parents=True, exist_ok=True)
    entries = list(pack.entries.values())
    print(f"{len(entries)} mods:")
    for entry in entries:
        entry["path"] = download(entry, args.cache)

    if mods.exists():
        shutil.rmtree(mods)
    mods.mkdir(parents=True)
    for entry in entries:
        shutil.copy2(entry["path"], mods / entry["file"]["filename"])

    addon = find_addon(args.loader, args.addon)
    if addon:
        check_addon(addon)
        shutil.copy2(addon, mods / addon.name)
        print(f"addon: {addon.name}")
    else:
        print(f"addon: none found in {args.loader}/build/libs; build it, or pass --addon")

    lock = "\n".join(f"{e['role']:<13}{e['title']} {e['version']}  {e['file']['filename']}" for e in entries)
    (out / "mods.txt").write_text(lock + "\n")
    if args.mrpack:
        target = write_mrpack(pack, entries, addon, out, f"sbwnpc-{args.loader}-local")
        print(f"modpack: {target}")
    print(f"mods folder: {mods}")


if __name__ == "__main__":
    main()
