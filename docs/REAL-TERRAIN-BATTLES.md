# Battles on real terrain

Headless replays of scripted battles on the terrain of a local world save, so a fight seen in game
can be rerun and measured without restarting Minecraft. Each scenario copies the terrain round its
objective from the save into the GameTest world, spawns the squads from presets, issues the orders,
runs the fight for a set time, and checks it against the scenario's thresholds.

They are not part of the portable suite: they need a local save, so they are registered only when
`-PgameTestBattleSaves` is given.

## Running

Prepare the headless environment first ([HEADLESS-TESTS.md](HEADLESS-TESTS.md)), then:

```bash
source build/headless/env.sh
"$HEADLESS_GRADLE_BIN" --no-daemon runGameTestServer \
  -PgameTestFilter=realTerrainBattles \
  -PgameTestBattleSaves=$HOME/.minecraft/saves \
  -PgameTestReportName=battles
```

`-PgameTestBattles=lone-squad-hill-point` runs only the named scenarios (comma-separated). A battle
takes about four minutes: combat ticks are paced to real time for SBW's weapon timers.

The save is only read. Its region files round the objective are copied into the report first and
the terrain is restored from that copy. Blocks from other mods in the save need those mods on the
test server: put their jars into `neoforge/build/gametest/server/mods/` (for example Dynamic Trees
for `dynamictrees:` leaves), otherwise the restore fails with "Missing replay block".

## Scenarios

[`scenarios.json`](../neoforge/src/gameTest/resources/battles/scenarios.json), one entry per battle:

| Field | Meaning |
|---|---|
| `about` | Where the battle comes from, e.g. which playtest |
| `world` | Folder name of the save under the saves directory |
| `objective` | `[x, y, z]` of the point being attacked |
| `ticks` | How long the battle runs |
| `attackers`, `defenders` | Squads as `{ "preset": "SIXTEEN", "at": [x, z] }`, spawned on the ground there; each side has one owner, so a shared order makes a joint operation |
| `afterTicks` (in an attacker group) | Give this squad its ATTACK this many ticks after the others, as a reinforcement |
| `expect` | Optional overrides: `maxFriendlyShare` (default 0.35), `maxChurnPerManMinute` (2.0), `assault` and `taken` (both true: required) |

Attackers get an ATTACK on the objective at tick 100; defenders DEFEND it. NPCs a previous run left
in the area are removed first.

## Output

In `neoforge/build/reports/gameTest/<report>/battle-<scenario>/`:

- `summary.txt`: survivors per side, phases and when they began, whether the point was taken, fire
  gate counts and shares, post churn.
- `timeline.csv`: alive counts and the attack phase over time.
- The copied region files.

The metrics come from the server log after the battle's start marker, so the order and fire log
groups are switched on for the run:

- **friendlyShare**: the share of fire attempts held because an ally or vehicle was in the lane
  (`FRIENDLY_OR_VEHICLE`). High values mean men bunch or stand in each other's lanes.
- **readyShare**: the share of attempts that fired.
- **intermediateReachedPerManMinute**: posts reached on the way and replaced, per man per minute;
  men running back and forth between posts push it up.
- **exhaustedPerManMinute**: local post searches that found nothing.

## Limits

A battle is one run of a noisy fight: survivors and shares vary noticeably between runs, so compare
several runs before reading a difference into one. The thresholds guard against getting worse than
the current behaviour; they are not targets. The terrain is real but the squads are scripted
presets, not the exact men of the playtest.
