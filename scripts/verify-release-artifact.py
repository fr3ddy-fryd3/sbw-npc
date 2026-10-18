#!/usr/bin/env python3
"""Check that staged release jars match the enabled loader and pinned SBW input."""

from pathlib import Path
import re
import subprocess
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parent.parent


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


def main() -> None:
    gradle = properties(ROOT / "gradle.properties")
    profiles = {path.stem: properties(path) for path in (ROOT / "gradle/sbw").glob("*.properties")}
    enabled = {name for name, profile in profiles.items() if profile.get("status") == "enabled"}
    require(enabled, {"neoforge"}, "validated loader profiles")
    for name, profile in profiles.items():
        require(profile.get("loader"), name, f"{name} profile loader")
        if name not in enabled:
            require(profile.get("status"), "audit-only", f"{name} profile status")

    profile = profiles["neoforge"]
    require(profile.get("minecraft"), gradle["minecraft_version"], "profile Minecraft version")
    require(profile.get("version"), gradle["superbwarfare_version"], "pinned SBW version")
    expected_jar = f"sbwnpc-{gradle['mod_version']}-mc{gradle['minecraft_version']}-neoforge.jar"
    jars = sorted(path.name for path in (ROOT / "build/libs").glob("*.jar"))
    require(jars, [expected_jar], "staged release jars")

    report = properties(ROOT / "build/reports/sbw/input.txt")
    require(report.get("mode"), "published", "SBW input mode")
    for key in ("loader", "status", "minecraft", "version", "coordinate", "repository", "sourceRevision", "sha512"):
        require(report.get(f"profile.{key}"), profile.get(key), f"SBW input {key}")
    require(report.get("artifact.sha512"), profile["sha512"], "SBW artifact checksum")

    jar = ROOT / "build/libs" / expected_jar
    with zipfile.ZipFile(jar) as archive:
        require(archive.testzip(), None, "JAR CRC")
        required = {
            "META-INF/neoforge.mods.toml",
            "mixins.sbwnpc.json",
            "com/sbwnpc/squad/combat/BuildFlags.class",
            "assets/sbwnpc/lang/en_us.json",
            "data/sbwnpc/loot_table/blocks/barracks.json",
        }
        missing = required.difference(archive.namelist())
        require(missing, set(), "required JAR resources")
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        require(metadata["mods"][0]["modId"], gradle["mod_id"], "JAR mod ID")
        require(metadata["mods"][0]["version"], gradle["mod_version"], "JAR mod version")

    flags = subprocess.run(
        ["javap", "-constants", "-classpath", str(jar), "com.sbwnpc.squad.combat.BuildFlags"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if not re.search(r"\bDEBUG_ENABLED\s*=\s*false;", flags):
        raise ValueError("Production JAR must have DEBUG_ENABLED = false")
    print(f"Verified {expected_jar} with pinned NeoForge SBW input and production flags")


if __name__ == "__main__":
    try:
        main()
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Release artifact verification failed: {error}") from error
