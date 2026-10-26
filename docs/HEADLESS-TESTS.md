# Server GameTests

Real server scenes with real NPCs, run headless: no Minecraft client, graphics session, Xvfb or GPU.
They test what unit tests cannot: navigation, collisions, real SBW bullets and damage, the Brain and
the HTN controller together. A passing scene still proves only what it measures; how well tactics
work is also checked in game.

## Setup

From the root of a clone:

```bash
bash setup-minecraft-headless.sh
```

[setup-minecraft-headless.sh](../setup-minecraft-headless.sh) prepares Java 21, Gradle 8.14.2,
SuperbWarfare and the NeoForge dependencies, then runs three server smoke scenes: a march over flat
ground, climbing and descending a ladder, and machine gunners and snipers taking a small watchtower.
It exits non-zero if a download, the build or a required scene fails. This checks the environment;
the full suite is a separate run.

Linux x86_64/aarch64 with glibc is supported. First setup needs the network. A practical guide for a
cloud machine is 8 GB of RAM and 10 GB of free disk; that is a recommendation, not a measured
minimum.

If `curl`, `python3`, `unzip`, `tar`, `sha256sum` or system CA certificates are missing, the script
installs them with `apt-get` as root or with passwordless sudo; on another distribution install them
yourself. `--no-system-install` forbids installing system packages. Java comes from `JAVA_HOME` or a
standard JDK 21 install; otherwise Temurin 21 is downloaded through the
[Adoptium API](https://api.adoptium.net/q/swagger-ui/) and checked against the SHA256 in its
metadata. Gradle is downloaded from the official distribution and checked against a pinned SHA256;
the wrapper's mirror settings are left alone. If `SuperbWarfare/` is missing, the script
downloads the pinned commit `5b92ebe1` (0.8.9.1 for Minecraft 1.21.1, with Rhino from `libs/`) and
checks its SHA256; an existing checkout is kept as it is, and an incomplete one stops the script
rather than being overwritten.

## Running

```bash
# Prepare in a cloud agent's setup phase, without running scenes:
bash setup-minecraft-headless.sh --prepare

# Afterwards:
bash setup-minecraft-headless.sh --unit
bash setup-minecraft-headless.sh --filter attackPastRidgeUnderFire,blockedMinorityResumesFireOnUnevenGround --report attack
bash setup-minecraft-headless.sh --group overwatch --report overwatch
bash setup-minecraft-headless.sh --all --report full

# Repeat already prepared scenes without the network:
bash setup-minecraft-headless.sh --offline --report repeat
```

| Option | Meaning |
|---|---|
| `--prepare` | Compile and prepare the server without running tests |
| `--unit` | Run JUnit instead of GameTests |
| `--all` | Run every registered GameTest |
| `--filter METHODS` | Run the GameTest methods with these exact names, comma-separated. An unknown name stops the run with `Unknown GameTest` and the list of available ones |
| `--group GROUPS` | Run the GameTests of these domains, comma-separated (see below). An unknown name stops the run with `Unknown GameTest group` and the list of known ones. Not combined with `--filter` |
| `--report NAME` | Report directory name (default `cloud-YYYYmmdd-HHMMSS`). ASCII letters, digits, `-` and `_` only; a repeated name overwrites the report, so give runs you want to compare different names |
| `--offline` | Use cached downloads and Gradle dependencies only |
| `--no-system-install` | Don't install missing base tools through `apt-get` |

`--prepare` warms up the server's dependencies. JUnit needs more: before the first
`--unit --offline`, run `--unit` once with the network. To keep the Gradle cache between cloud
sessions, set `GRADLE_USER_HOME` before running. Local tools and archives live in `build/headless/`.
`HEADLESS_GRADLE_HEAP=4G` sets Gradle's memory, `HEADLESS_GRADLE_BIN=/path/gradle` uses an existing
Gradle 8.14.2. The script limits the build to two workers and no parallel tasks.

Domains for `--group`, to run what a change touches (a class may sit in several):

| Group | Scenes about |
|---|---|
| `overwatch` | Towers, sentries, far defence, scaffolding, observers' gaze |
| `orders` | Attack, defend, patrol, follow, withdrawal, manoeuvres, areas, the HTN |
| `posts` | Searching firing posts and cover, firing lines, target memory |
| `movement` | Infantry marches, ladders, routes |
| `combat` | Fights, allies' deaths, sidearms, ammunition, hostile mobs |
| `perception` | Noticing and hearing |
| `vehicles` | Seats, supply, transport, bends, gunners' aim and rank |
| `aircraft` | Helicopters and drones |
| `fire-support` | Mortar crews and fire missions |
| `logistics` | Faction supply, experience, progression |
| `performance` | Load and search-work measurements |

A new scene class goes into `GameTestRegistration` and into a group in `GameTestGroups`.

For manual commands, `source build/headless/env.sh`, then for example
`"$HEADLESS_GRADLE_BIN" runGameTestServer -PgameTestFilter=moveAcrossFlatGround -PgameTestReportName=move`.
Gradle properties the scenes read:

| Property | Meaning |
|---|---|
| `gameTestFilter` | Method names to run, as `--filter` |
| `gameTestGroup` | Domains to run, as `--group` |
| `gameTestReportName` | Report directory name, as `--report` |
| `gameTestReplay` | Path to a saved battle fixture; only then are the `SavedBattleGameTests` scenes registered |
| `gameTestWatchNpc` | The NPC whose trace a saved battle replay records |
| `gameTestNegativeControl` | `true` breaks the flat-ground move scene on purpose, to check it can fail |

Without a filter every registered scene runs. After the run Gradle requires a fresh server log
containing `GAME TESTS COMPLETE`: a JVM that exits before the scenes ran counts as a failure even with
exit code 0. GameTests are a separate run; `./gradlew test` runs JUnit only.

To check the bench can fail at all:

```bash
"$HEADLESS_GRADLE_BIN" runGameTestServer -PgameTestReportName=negative-control -PgameTestNegativeControl=true
```

This switches off the AI of the NPC on the flat route only. Expected: `moveAcrossFlatGround` fails its
progress check, the platform scene passes, and Gradle reports a failure.

## Output

- Setup and run log: `build/headless/logs/<report>.log`.
- Server log, CSV and TXT files written by the scenes: `neoforge/build/reports/gameTest/<report>/`.
- JUnit XML/HTML: `<module>/build/test-results/test/`, `<module>/build/reports/tests/test/`.

Scenes that count search work (`htnSixHundredActiveNpcMovementBudget`,
`fourDefendSquadsMeasureNativeWorkAndHoldSeparateAreas`, through `NativeCombatWork`) also write
`operation-work.csv` (per NPC: navigation calls, native A* runs, the most per tick and per 20 ticks,
repeated requests within a tick, position searches) and `operation-work.txt` (distributions of A*
call and server tick durations). These are server measurements with their own overhead, not client
FPS and not a comparison of two versions.

The script does not copy the jar into an installed Minecraft and does not touch user worlds. On a
dedicated server SBW logs that its client-only `ClientLevelMixin` is unavailable; that is expected
and harmless.

## Things to know

- **Don't run two GameTest processes in one clone at once**: they share
  `neoforge/build/gametest/server/`. To run in parallel, use separate clones or worktrees, each
  with its own prepared `build/headless/`.
- **The test world persists between runs** in `neoforge/build/gametest/server/`. Entities a run
  left behind (for example after an interrupted run) can load into the next one; a scene that
  spawns NPCs should clear its area first.
- **Combat scenes pace ticks to real time** (`CombatTickPacer`, at least 50 ms a tick): SBW weapon
  timers run on wall-clock time, and an unpaced server ticks faster than they can fire.
- **Real projectiles need an observer.** With no player near a fight, the mod resolves shots as a
  hitscan roll (`OffscreenFire`) instead of real SBW rounds. Combat scenes that measure rounds add a
  fake player (`FakePlayerFactory`) near the fight and check `OffscreenFire.hasWitness`.
- **Debug logs are on in scenes** that set `DebugFlags.enabledGroups`; others log only test results.

## Limits

The scenes run the real SmartBrainLib behaviours, `VehicleAwareNavigation`, `NpcMoveControl`,
collisions, tactics and SBW weapons, but on built arenas: natural complex cover, deep pits, Dynamic
Trees, narrow passes and rivers are not covered. Targets with raised health let deployment and fire
be observed but do not measure how well ordinary enemies are killed. A line check confirms a line
held for its window, not in every later phase. Success of a MOVE or an isolated firefight does not
prove a whole battle or a player's case is fixed.

## Where scenes live

| File | Contents |
|---|---|
| [GameTestRegistration.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/GameTestRegistration.kt) | Registers the scene classes and applies `gameTestGroup` and `gameTestFilter` |
| [GameTestGroups.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/GameTestGroups.kt) | The scene classes by domain; a registered class in no group stops the server start |
| [InfantryMovementGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/InfantryMovementGameTests.kt), [LadderNavigationGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/LadderNavigationGameTests.kt), [ScaffoldingGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/ScaffoldingGameTests.kt) | Movement: MOVE orders, arrival, ladders, scaffolding |
| [HtnTacticsGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/HtnTacticsGameTests.kt) | Squads under the HTN: joint attacks, taking a point, reinforcements, water, withdrawal on losses, 600-NPC movement |
| [AttackDeploymentGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/AttackDeploymentGameTests.kt), [AttackTrace.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/AttackTrace.kt) | 16 riflemen attacking stationary targets; the trace measures the line, who fired, ammunition, rounds and damage |
| [TerrainAttackGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/TerrainAttackGameTests.kt) | Attacks over a ridge and on uneven ground |
| [FiringPostSearchGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/FiringPostSearchGameTests.kt), [TacticalSearchGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/TacticalSearchGameTests.kt), [CoverBudgetGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/CoverBudgetGameTests.kt) | Position search: reachable firing posts, route reuse, budgets |
| [DefensiveOverwatchGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/DefensiveOverwatchGameTests.kt), [RoofedOverwatchGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/RoofedOverwatchGameTests.kt) | Snipers and machine gunners on towers |
| [HearingGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/HearingGameTests.kt), [TargetMemoryGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/TargetMemoryGameTests.kt), [FoliageCombatGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/FoliageCombatGameTests.kt), [FireSupportGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/FireSupportGameTests.kt), [CombatIdleGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/CombatIdleGameTests.kt) | Hearing, target memory, fire through foliage, fire support, idle men in a fight |
| [SavedBattleGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/SavedBattleGameTests.kt), [SavedBattleReplay.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/SavedBattleReplay.kt), [CombatParticipationTrace.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/CombatParticipationTrace.kt) | Replaying a battle saved from a real world (`gameTestReplay`) and why men idled in it |
| [CombatPerformanceGameTests.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/CombatPerformanceGameTests.kt), [NativeCombatWork.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/NativeCombatWork.kt) | Server cost of large fights and counting search work |
| Other `*GameTests.kt` | Supply, vehicles, mortars, helicopters, sidearms, ammunition, experience, squad areas, progression |
| [CombatTickPacer.kt](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/CombatTickPacer.kt) | The real-time tick pacing described above |
| [structure/](../neoforge/src/gameTest/resources/data/sbwnpc/structure/) | Empty arena templates (`movement_arena`, `attack_arena`, `terrain_arena`, ...); floors and obstacles are placed by the scenes |
| [neoforge/build.gradle.kts](../neoforge/build.gradle.kts) | The `gameTest` source set, `runGameTestServer`, the mods loaded and archiving the log |
