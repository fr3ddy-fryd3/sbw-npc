#!/bin/bash
# Prepares a Claude Code cloud session to build and test sbw-npc: scripts/setup.sh clones the
# SuperbWarfare checkout that settings.gradle.kts includeBuild()s (it's gitignored), then warms
# the Gradle caches by compiling main and test sources, so the first `build` or `test` is quick.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

exec "$CLAUDE_PROJECT_DIR/scripts/setup.sh" testClasses
