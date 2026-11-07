#!/usr/bin/env python3
"""Check that a staged release jar matches its loader's enabled profile and the pinned SBW input.

One loader per run, because a Gradle run configures one loader and stages only its jar into build/libs:

    ./gradlew build && python3 scripts/verify-release-artifact.py
    ./gradlew -Ploader=fabric :fabric:build && python3 scripts/verify-release-artifact.py --loader fabric
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parent.parent
LOADERS = ("neoforge", "fabric", "forge")
STATUSES = {"enabled", "audit-only", "preview"}

# What a jar of each loader must contain besides the shared classes. The loot table folder is the
# Minecraft version's own name (loot_table on 1.21, loot_tables on 1.20.1); the other name must be absent.
COMMON_RESOURCES = {"com/sbwnpc/squad/combat/BuildFlags.class", "assets/sbwnpc/lang/en_us.json"}
LAYOUT = {
    "neoforge": {
        "metadata": "META-INF/neoforge.mods.toml",
        "resources": {"mixins.sbwnpc.json", "data/sbwnpc/loot_table/blocks/barracks.json"},
        "absent": {"data/sbwnpc/loot_tables/blocks/barracks.json"},
    },
    "fabric": {
        "metadata": "fabric.mod.json",
        "resources": {"sbwnpc.fabric.mixins.json", "data/sbwnpc/loot_table/blocks/barracks.json"},
        "absent": {"data/sbwnpc/loot_tables/blocks/barracks.json"},
    },
    "forge": {
        "metadata": "META-INF/mods.toml",
        "resources": {
            "sbwnpc.forge.mixins.json",
            "sbwnpc.forge.refmap.json",
            "pack.mcmeta",
            "data/sbwnpc/loot_tables/blocks/barracks.json",
        },
        "absent": {"data/sbwnpc/loot_table/blocks/barracks.json"},
    },
}


def properties(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, separator, value = line.partition("=")
            if not separator:
                raise ValueError(f"Malformed property in {path}: {line}")
            result[key.strip()] = value.strip()
    return result


def require(actual: object, expected: object, name: str) -> None:
    if actual != expected:
        raise ValueError(f"{name}: expected {expected!r}, got {actual!r}")


def mod_metadata(loader: str, archive: zipfile.ZipFile) -> tuple[str, str]:
    """The mod ID and version the loader will read from the jar."""
    raw = archive.read(LAYOUT[loader]["metadata"]).decode("utf-8")
    if loader == "fabric":
        metadata = json.loads(raw)
        return metadata["id"], metadata["version"]
    mod = tomllib.loads(raw)["mods"][0]
    return mod["modId"], mod["version"]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_remapped(loader: str, jar_name: str) -> None:
    """Forge (SRG) and Fabric (intermediary) modules build a development jar with Mojang names in
    <module>/build/devlibs, which loads on neither, and remap it into <module>/build/libs. The staged
    jar must be the remapped one: byte-identical to the module's output and different from devlibs."""
    if loader == "neoforge":
        return
    staged = ROOT / "build/libs" / jar_name
    remapped = ROOT / loader / "build/libs" / jar_name
    require(remapped.is_file(), True, f"{loader} module output {remapped}")
    require(sha256(staged), sha256(remapped), f"staged {loader} jar matches the module's remapped jar")
    development = ROOT / loader / "build/devlibs" / jar_name
    if development.is_file():
        require(sha256(staged) != sha256(development), True, f"staged {loader} jar is not the Mojang-named development jar")


def verify(loader: str) -> str:
    gradle = properties(ROOT / "gradle.properties")
    profiles = {path.stem: properties(path) for path in (ROOT / "gradle/sbw").glob("*.properties")}
    for name, profile in profiles.items():
        require(profile.get("loader"), name, f"{name} profile loader")
        if profile.get("status") not in STATUSES:
            raise ValueError(f"{name} profile status: expected one of {sorted(STATUSES)}, got {profile.get('status')!r}")
    profile = profiles[loader]
    require(profile.get("status"), "enabled", f"{loader} profile status")

    # gradle.properties describes the NeoForge build; Forge carries its own Minecraft version.
    minecraft = gradle["minecraft_version"] if loader != "forge" else properties(ROOT / "gradle/forge.properties")["forge_minecraft_version"]
    require(profile.get("minecraft"), minecraft, "profile Minecraft version")
    if loader == "neoforge":
        require(profile.get("version"), gradle["superbwarfare_version"], "pinned SBW version")
    expected_jar = f"sbwnpc-{gradle['mod_version']}-mc{minecraft}-{loader}.jar"
    jars = sorted(path.name for path in (ROOT / "build/libs").glob("*.jar"))
    require(jars, [expected_jar], "staged release jars")

    check_remapped(loader, expected_jar)

    report = properties(ROOT / "build/reports/sbw/input.txt")
    require(report.get("mode"), "published", "SBW input mode")
    for key in ("loader", "status", "minecraft", "version", "coordinate", "repository", "sourceRevision", "sha512"):
        require(report.get(f"profile.{key}"), profile.get(key), f"SBW input {key}")
    require(report.get("artifact.sha512"), profile["sha512"], "SBW artifact checksum")

    jar = ROOT / "build/libs" / expected_jar
    layout = LAYOUT[loader]
    with zipfile.ZipFile(jar) as archive:
        require(archive.testzip(), None, "JAR CRC")
        names = set(archive.namelist())
        missing = (COMMON_RESOURCES | layout["resources"] | {layout["metadata"]}).difference(names)
        require(missing, set(), "required JAR resources")
        require(names & layout["absent"], set(), "JAR resources of another Minecraft version")
        mod_id, version = mod_metadata(loader, archive)
        require(mod_id, gradle["mod_id"], "JAR mod ID")
        require(version, gradle["mod_version"], "JAR mod version")
        if loader == "forge":
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
            require("MixinConfigs: sbwnpc.forge.mixins.json" in manifest, True, "Forge manifest MixinConfigs")

    flags = subprocess.run(
        ["javap", "-constants", "-classpath", str(jar), "com.sbwnpc.squad.combat.BuildFlags"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if not re.search(r"\bDEBUG_ENABLED\s*=\s*false;", flags):
        raise ValueError("Production JAR must have DEBUG_ENABLED = false")
    return expected_jar


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--loader", choices=LOADERS, default="neoforge", help="the loader whose jar is staged in build/libs")
    args = parser.parse_args()
    jar = verify(args.loader)
    print(f"Verified {jar} with the pinned {args.loader} SBW input and production flags")


if __name__ == "__main__":
    try:
        main()
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Release artifact verification failed: {error}") from error
