#!/usr/bin/env bash
# Sets up a checkout of sbw-npc to build, test and deploy on a contributor's machine.
#
# Clones the SuperbWarfare checkout that settings.gradle.kts includeBuild()s (it is gitignored),
# then runs the given Gradle tasks (default: build). The cloud SessionStart hook runs this too.
#
#   scripts/setup.sh                    clone SBW if missing, then ./gradlew build
#   scripts/setup.sh test               any Gradle tasks instead of build (buildDevelop, testClasses, ...)
#   scripts/setup.sh --deploy           also copy the jar into the mods folder, replacing older sbwnpc jars
#   scripts/setup.sh --refabricated     also clone and build SuperbWarfare ReFabricated (Fabric) on its own
#
# Environment overrides: SBW_REPO, SBW_BRANCH, REFAB_REPO, REFAB_BRANCH, MODS_DIR.
# On Windows, run it from Git Bash or WSL.
set -euo pipefail

SBW_REPO="${SBW_REPO:-https://github.com/Mercurows/SuperbWarfare.git}"
# The addon tracks a SuperbWarfare release branch, not upstream `1.21` tip (which has broken its APIs).
SBW_BRANCH="${SBW_BRANCH:-0.8.9.2-1.21}"
# ReFabricated is a Fabric fork the addon does not build against yet (that is the port in #15), so it
# goes into its own folder and is never includeBuild()ed: its Loom build can't substitute for NeoForge SBW.
REFAB_REPO="${REFAB_REPO:-https://github.com/CrawKatt/SuperbWarfareReFabricated.git}"
REFAB_BRANCH="${REFAB_BRANCH:-0.8.9.2-1.21-Fabric}"
# The launcher only reads the flat mods folder, not a mods/1.21.1/ subfolder.
MODS_DIR="${MODS_DIR:-$HOME/.minecraft/mods}"

deploy=false
refabricated=false
tasks=()
for arg in "$@"; do
  case "$arg" in
    --deploy) deploy=true ;;
    --refabricated) refabricated=true ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
    *) tasks+=("$arg") ;;
  esac
done
[ ${#tasks[@]} -gt 0 ] || tasks=(build)

cd "$(dirname "$0")/.."

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
# Gradle itself needs Java 17+ to start; the build then provisions its Java 21 toolchain via foojay.
java_major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
  echo "Java 17 or newer is required to run Gradle (Java 21 recommended), found: ${java_major:-none}" >&2
  exit 1
fi

# Clones $2 at branch $3 into $1, or warns when an existing checkout is on another branch.
# An existing checkout is never touched: it may hold someone's local work.
checkout() {
  local dir=$1 repo=$2 branch=$3
  if [ ! -d "$dir/.git" ]; then
    echo "Cloning $repo ($branch) into $dir..." >&2
    git clone --depth 1 --branch "$branch" "$repo" "$dir" >&2
    return
  fi
  local current
  current=$(git -C "$dir" rev-parse --abbrev-ref HEAD)
  if [ "$current" != "$branch" ]; then
    echo "Warning: $dir is on branch $current, expected $branch. Leaving it as is." >&2
  fi
}

# Maven Central sometimes answers 429 under load, so retry a couple of times before giving up.
gradle_with_retry() {
  local attempt
  for attempt in 1 2 3; do
    if ./gradlew --console=plain "$@" >&2; then
      return 0
    fi
    echo "Gradle attempt $attempt failed" >&2
    [ "$attempt" -lt 3 ] && sleep $((attempt * 15))
  done
  return 1
}

checkout SuperbWarfare "$SBW_REPO" "$SBW_BRANCH"
gradle_with_retry "${tasks[@]}"

if $deploy; then
  jar=$(ls build/libs/sbwnpc-*.jar 2>/dev/null | grep -v -e '-sources' -e '-javadoc' | head -n 1 || true)
  if [ -z "$jar" ]; then
    echo "No jar in build/libs to deploy; run with a task that builds one (build or buildDevelop)" >&2
    exit 1
  fi
  mkdir -p "$MODS_DIR"
  # Two versions of the mod in mods/ would both load.
  find "$MODS_DIR" -maxdepth 1 -name 'sbwnpc-*.jar' ! -name "$(basename "$jar")" -print -delete >&2
  cp "$jar" "$MODS_DIR/"
  echo "Deployed $(basename "$jar") to $MODS_DIR" >&2
fi

if $refabricated; then
  checkout SuperbWarfareReFabricated "$REFAB_REPO" "$REFAB_BRANCH"
  (cd SuperbWarfareReFabricated && gradle_with_retry build)
  echo "ReFabricated jars: $(ls SuperbWarfareReFabricated/build/libs/*.jar 2>/dev/null | tr '\n' ' ')" >&2
fi
