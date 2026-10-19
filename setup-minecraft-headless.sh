#!/usr/bin/env bash
# Bootstrap the project's real NeoForge GameTest server on Linux (no display needed).
set -Eeuo pipefail

usage() {
    cat <<'HELP'
Usage: bash setup-minecraft-headless.sh [options]

Default: install missing tools, prepare dependencies, run three smoke GameTests.
  --prepare           Compile and prepare the server without running tests
  --unit              Run JUnit instead of GameTest
  --all               Run all registered GameTests
  --filter METHODS    Run exact GameTest method names, separated by commas
  --report NAME       Report directory name (default: cloud-YYYYmmdd-HHMMSS)
  --offline           Use cached downloads and Gradle dependencies only
  --no-system-install Do not install missing base tools through apt-get
  -h, --help          Show this help

Requires Linux x86_64/aarch64 with glibc. Uses JAVA_HOME/installed Java 21,
otherwise installs Temurin 21 locally. Gradle 9.5.0 is installed locally.
Downloads and logs: build/headless/; test reports: neoforge/build/reports/gameTest/.
Optional: GRADLE_USER_HOME for a persistent cache; HEADLESS_GRADLE_HEAP=4G;
HEADLESS_GRADLE_BIN=/absolute/path/to/gradle (must be 9.5.0).
Existing SuperbWarfare sources are retained. A missing directory is restored
from the pinned upstream 0.8.9.1 commit. No Minecraft client is launched.
HELP
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
mode=smoke
filter=moveAcrossFlatGround,marchClimbsAndDescendsLadder,defendingSupportsReserveSmallTower
report="cloud-$(date -u +%Y%m%d-%H%M%S)"
offline=false
system_install=true
while (($#)); do
    case "$1" in
        --prepare) mode=prepare; shift ;;
        --unit) mode=unit; shift ;;
        --all) mode=all; shift ;;
        --filter) (($# >= 2)) || die '--filter requires method names'; mode=filter; filter=$2; shift 2 ;;
        --report) (($# >= 2)) || die '--report requires a name'; report=$2; shift 2 ;;
        --offline) offline=true; shift ;;
        --no-system-install) system_install=false; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "Unknown option: $1 (see --help)" ;;
    esac
done
[[ $report =~ ^[A-Za-z0-9_-]+$ ]] || die 'Report name must contain only ASCII letters, digits, _ and -'
[[ $filter =~ ^[A-Za-z0-9_]+(,[A-Za-z0-9_]+)*$ ]] || die 'Filter must contain exact method names separated by commas'
[[ $(uname -s) == Linux ]] || die 'This bootstrap supports Linux only'
case "$(uname -m)" in
    x86_64) architecture=x64 ;;
    aarch64|arm64) architecture=aarch64 ;;
    *) die 'Only x86_64 and aarch64 are supported' ;;
esac
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd -- "$root"
[[ -f settings.gradle.kts ]] || die 'Run the script from a complete sbw-npc checkout'
work="$root/build/headless"
mkdir -p "$work/downloads" "$work/tools" "$work/logs"
# The scenes share a single server world; concurrent bootstrap runs are unsafe.
mkdir "$work/run.lock" 2>/dev/null || die "Another run is active; if it crashed, remove $work/run.lock"
log="$work/logs/$report.log"
finish() {
    local result=$?
    rmdir "$work/run.lock" || true
    if ((result != 0)); then
        printf 'Bootstrap failed (exit %s). Log: %s\n' "$result" "$log" >&2
    fi
}
trap finish EXIT
exec > >(tee "$log") 2>&1
printf 'Headless bootstrap: %s\nRepository: %s\nMode: %s\n' "$report" "$root" "$mode"

missing=()
command -v curl >/dev/null || missing+=(curl)
command -v python3 >/dev/null || missing+=(python3)
command -v unzip >/dev/null || missing+=(unzip)
command -v tar >/dev/null || missing+=(tar)
command -v sha256sum >/dev/null || missing+=(coreutils)
[[ -s /etc/ssl/certs/ca-certificates.crt ]] || missing+=(ca-certificates)
if ((${#missing[@]})); then
    if ! $system_install || $offline; then
        die "Missing tools: ${missing[*]}. Install them first or enable system installation"
    fi
    command -v apt-get >/dev/null || die "Install these tools using your package manager: ${missing[*]}"
    elevate=()
    if ((EUID != 0)); then
        if ! command -v sudo >/dev/null || ! sudo -n true; then
            die "Installing ${missing[*]} requires root or passwordless sudo"
        fi
        elevate=(sudo -n)
    fi
    "${elevate[@]}" apt-get update
    "${elevate[@]}" env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing[@]}"
fi

download() {
    local url=$1 destination=$2
    [[ -s $destination ]] && return
    ! $offline || die "Not cached for offline use: $destination"
    printf 'Downloading: %s\n' "$url"
    curl --fail --location --silent --show-error --retry 3 --connect-timeout 30 --max-time 900 \
        --output "$destination.part" "$url"
    mv -- "$destination.part" "$destination"
}
verify() {
    local checksum=$1 file=$2
    [[ $checksum =~ ^[a-f0-9]{64}$ ]] || die "Invalid SHA256 for $file"
    printf '%s  %s\n' "$checksum" "$file" | sha256sum --check --status || die "SHA256 mismatch: $file (remove it and retry)"
}
is_java21() {
    [[ -x $1/bin/java && -x $1/bin/javac ]] && \
        "$1/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "21([.\"]|$)'
}
java_dir=${JAVA_HOME:-}
if ! is_java21 "$java_dir"; then
    java_dir=
    for candidate in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21-openjdk-amd64 \
        /usr/lib/jvm/java-21-openjdk-arm64 "$work/tools/jdk-21"; do
        if is_java21 "$candidate"; then java_dir=$candidate; break; fi
    done
fi
if [[ -z $java_dir ]]; then
    metadata="$work/downloads/temurin-21-$architecture.json"
    download "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$architecture&image_type=jdk&os=linux&vendor=eclipse" "$metadata"
    mapfile -t jdk_package < <(python3 - "$metadata" <<'PY'
import json, sys
package = json.load(open(sys.argv[1]))[0]['binary']['package']
print(package['link'])
print(package['checksum'])
PY
    )
    ((${#jdk_package[@]} == 2)) || die 'Could not read Temurin package metadata'
    archive="$work/downloads/temurin-21-$architecture.tar.gz"
    download "${jdk_package[0]}" "$archive"
    verify "${jdk_package[1]}" "$archive"
    mkdir -p "$work/tools/jdk-21"
    tar -xzf "$archive" --strip-components=1 -C "$work/tools/jdk-21"
    java_dir="$work/tools/jdk-21"
fi
is_java21 "$java_dir" || die 'Java 21 JDK could not start (glibc Linux is required)'
export JAVA_HOME="$java_dir"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

sbw_commit=5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816
if [[ ! -e SuperbWarfare ]]; then
    archive="$work/downloads/superbwarfare-$sbw_commit.tar.gz"
    download "https://codeload.github.com/Mercurows/SuperbWarfare/tar.gz/$sbw_commit" "$archive"
    verify 79c4bc48c551ec2e0d53abce692d23c3b99b44a4105aa7086f702f5425996483 "$archive"
    unpack="$work/tools/sbw-unpack"
    mkdir -p "$unpack"
    tar -xzf "$archive" -C "$unpack"
    mv -- "$unpack/SuperbWarfare-$sbw_commit" "$root/SuperbWarfare"
    printf '%s\n' "$sbw_commit" > SuperbWarfare/.headless-upstream-commit
    rmdir "$unpack"
fi
[[ -f SuperbWarfare/build.gradle.kts && -f SuperbWarfare/libs/rhino-1.8.1-SNAPSHOT.jar ]] || \
    die 'Existing SuperbWarfare directory is incomplete; it was left untouched'
printf 'SBW: %s\n' "$(cat SuperbWarfare/.headless-upstream-commit 2>/dev/null || printf 'existing local sources (retained)')"

gradle_bin=${HEADLESS_GRADLE_BIN:-"$work/tools/gradle-9.5.0/bin/gradle"}
if [[ -z ${HEADLESS_GRADLE_BIN:-} && ! -x $gradle_bin ]]; then
    archive="$work/downloads/gradle-9.5.0-bin.zip"
    download https://services.gradle.org/distributions/gradle-9.5.0-bin.zip "$archive"
    verify 553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746 "$archive"
    unzip -q -o "$archive" -d "$work/tools"
fi
[[ -x $gradle_bin ]] || die "Gradle executable missing: $gradle_bin"
"$gradle_bin" --version > "$work/gradle-version.txt"
grep -qx 'Gradle 9.5.0' "$work/gradle-version.txt" || die 'Use Gradle 9.5.0 to match this project'
heap=${HEADLESS_GRADLE_HEAP:-4G}
[[ $heap =~ ^[1-9][0-9]*[MG]$ ]] || die 'HEADLESS_GRADLE_HEAP must be a size such as 4G'
args=(--no-daemon --console=plain --max-workers=2 --no-parallel
    "-Dorg.gradle.jvmargs=-Xmx$heap -Dfile.encoding=UTF-8"
    -Pkotlin.compiler.execution.strategy=in-process)
$offline && args+=(--offline)
case "$mode" in
    prepare) args+=(prepareGameTestServerRun) ;;
    unit) args+=(test) ;;
    *)
        args+=(runGameTestServer "-PgameTestReportName=$report")
        [[ $mode == all ]] || args+=("-PgameTestFilter=$filter")
        ;;
esac
# PATH must expand when env.sh is sourced, not while generating the file.
# shellcheck disable=SC2016
printf 'JAVA_HOME=%q\nexport JAVA_HOME\nexport PATH="$JAVA_HOME/bin:$PATH"\nHEADLESS_GRADLE_BIN=%q\nexport HEADLESS_GRADLE_BIN\n' \
    "$JAVA_HOME" "$gradle_bin" > "$work/env.sh"
printf 'Running:'; printf ' %q' "$gradle_bin" "${args[@]}"; printf '\n'
"$gradle_bin" "${args[@]}"
printf '\nHeadless %s completed. Log: %s\nEnvironment: %s/env.sh\n' "$mode" "$log" "$work"
if [[ $mode != unit && $mode != prepare ]]; then
    printf 'GameTest reports: %s/neoforge/build/reports/gameTest/%s/\n' "$root" "$report"
fi
