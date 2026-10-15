#!/bin/bash
# Prepares a Claude Code cloud session using the pinned SBW artifact through scripts/setup.sh.
# Compiles main and test sources to warm Gradle caches before the first build or test.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

exec "$CLAUDE_PROJECT_DIR/scripts/setup.sh" testClasses
