#!/bin/bash
# Prepares a Claude Code cloud session to build and test sbw-npc:
# clones the SuperbWarfare checkout that settings.gradle.kts includeBuild()s (it's gitignored),
# then warms the Gradle caches by compiling main and test sources.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

SBW_REPO="${SBW_REPO:-https://github.com/Mercurows/SuperbWarfare.git}"
SBW_BRANCH="${SBW_BRANCH:-1.21}"

if [ ! -d SuperbWarfare/.git ]; then
  echo "Cloning SuperbWarfare ($SBW_BRANCH)..." >&2
  git clone --depth 1 --branch "$SBW_BRANCH" "$SBW_REPO" SuperbWarfare >&2
fi

# Downloads the wrapper distribution, Minecraft/NeoForge artifacts and every Maven dependency,
# and compiles both projects, so the session's first `./gradlew build` or `test` is quick.
./gradlew --console=plain testClasses >&2
