#!/usr/bin/env python3
"""Install a built NPC Squads JAR without overwriting an archive open by Minecraft."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
import zipfile


def install(source: Path, mods: Path) -> Path:
    if not source.name.startswith("sbwnpc-") or source.suffix != ".jar":
        raise ValueError("Expected a built sbwnpc-*.jar")
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:
            raise ValueError("The source JAR is corrupt")
        archive.getinfo("com/sbwnpc/squad/vehicle/Helicopters.class")
    mods.mkdir(parents=True, exist_ok=True)
    destination = mods / source.name
    others = [jar for jar in mods.glob("sbwnpc-*.jar") if jar != destination]
    if others:
        raise ValueError(f"Remove older addon versions before installing: {others}")
    descriptor, staging = tempfile.mkstemp(prefix=".sbwnpc-", suffix=".pending", dir=mods)
    os.close(descriptor)
    staging = Path(staging)
    try:
        shutil.copy2(source, staging)
        with staging.open("rb") as stream:
            os.fsync(stream.fileno())
        if hashlib.sha256(staging.read_bytes()).digest() != hashlib.sha256(source.read_bytes()).digest():
            raise ValueError("The staged JAR does not match the build")
        # Same filesystem: a running JVM retains its original inode and ZIP directory.
        # The replacement is loaded on the next full Minecraft restart.
        os.replace(staging, destination)
    finally:
        staging.unlink(missing_ok=True)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--mods", type=Path, default=Path.home() / ".minecraft" / "mods")
    args = parser.parse_args()
    destination = install(args.jar.resolve(), args.mods.expanduser().resolve())
    print(f"Installed {destination}; restart Minecraft to load this build.")
