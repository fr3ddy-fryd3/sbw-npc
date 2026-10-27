# Fabric and Forge ports: status and decisions

Working notes for #15 (Fabric, Minecraft 1.21.1, SuperbWarfare ReFabricated) and #16 (Forge,
Minecraft 1.20.1). The design is in [MULTILOADER-DESIGN.md](MULTILOADER-DESIGN.md) and the
SuperbWarfare API audit in [SBW-COMPATIBILITY.md](SBW-COMPATIBILITY.md); this file records what
the first builds showed. Measured 2026-10-19 on `develop` (a02de96) plus the `fabric` module.

## Decisions

- **Port from `develop`, not from a release.** Both issues said to start from 1.0.0; no such tag
  exists (the latest is v0.6.0), and `develop` already has the `common`/`neoforge` split the ports
  need. Gameplay is shared per release line, never forked per loader.
- **Fabric first.** Same Minecraft, Java and Mojang names as NeoForge; `common` compiles unchanged.
- **Forge second, by concentrating the 1.20.1 differences in a few seams** instead of copying
  files (see below).
- **Only the loader chosen with `-Ploader=<neoforge|fabric|forge>` is configured** (default `neoforge`).
  A NeoForge build never needs Loom or the Fabric Maven.

## Fabric (#15)

`./gradlew -Ploader=fabric :fabric:build` builds `fabric/build/libs/sbwnpc-<version>-mc1.21.1-fabric.jar`:
the shared sources, the Fabric entry points (`SquadFabricMod`, `SquadFabricClient`), the SuperbWarfare adapter
(`integration/sbw`, a copy of the NeoForge one with ReFabricated's registries and player data) and the mixins.

Checked on a dedicated Fabric server (Fabric Loader 0.17.2, ReFabricated 0.8.9.2, the local pack's mods): it starts,
SBW and the addon initialise, and `/summon sbwnpc:npc` gives an NPC with an SBW rifle and sidearm that stays alive.
Played on a client by fiol-dev (Fabric Loader 0.17.2 with Sodium, Lithium, JEI, JourneyMap and others): creative
tab items, screens, the command HUD, squads and orders, and the JourneyMap squad squares and order menu work. Faction
selection deliberately cannot be closed with Esc. Not checked: multiplayer, long sessions, saving and reloading. `./gradlew runServer` cannot be used for this: ReFabricated reflects on
intermediary class names and fails in a development environment, so test with the built jar.
The JourneyMap plugin (`integration/journeymap`) is a copy of the NeoForge one found through a `journeymap` entrypoint;
it compiles but has not run against JourneyMap.

What the Fabric side does instead of NeoForge's bus events: Fabric API events for ticks, lifecycle, entity load,
damage, death, joins and respawns; mixins (`GameEventMixin`, `ExplosionMixin`, `RespawnPositionMixin`,
`UseItemMixin`) where Fabric has no event; Fabric attachments for an entity's persistent data; ForgeConfigAPIPort
for the same `ModConfigSpec` config files; Fabric networking over `ServerPayloadHandlers` in common. Screens, the HUD
and the NPC renderer are shared with NeoForge from `common/src/client`.

Differences and gaps:

- NPCs wade at the vanilla pace: there is no swim speed attribute on Fabric.
- NPCs hold an SBW gun in the default arm pose and the ordinary item renderer draws it (`SbwClientPorts`): SBW's own
  pose and static gun model, and the aircraft level-of-detail rendering mixins, are not ported.
- `PerfProbe` (a debug aid) is not wired on Fabric.
- A deleted squad's entity is removed when it loads, instead of its join being cancelled.
- Fabric API has no event to cancel an entity joining or a death, so those two NeoForge behaviours differ slightly.

Build prerequisites found on the way:

- **Gradle 9.5.** The pinned ReFabricated jar was built with Loom 1.17.20; Loom refuses mods built
  by a newer Loom than its own, and 1.17 needs Gradle 9.5. The wrapper and the headless script
  moved from 8.14.2 to 9.5.0. The NeoForge build, `detekt`, ktlint, unit tests and GameTests pass on
  it. The headless script's checksum was computed from the copy served by the wrapper's mirror;
  compare it with Gradle's published value for 9.5.0. The foojay toolchain resolver is 1.0.0 because 0.8 fails to
  load on Gradle 9 when a JDK has to be downloaded.
- **Parchment is not used on Fabric.** `maven.parchmentmc.org` redirects to `ldtteam.jfrog.io`,
  which the cloud network policy does not allow. Parchment only adds parameter names, so the
  bytecode is the same; add the host and `loom.layered { parchment(...) }` to get them.
- **Maven Central rate limits (HTTP 429)** the shared cloud proxy; retrying later works.

Still to do for Fabric: in-game testing on a client; SBW's gun pose and static gun model; aircraft rendering;
a Fabric GameTest or server scene run in CI; the `enabled` profile and release artifact validation.

## Forge, Minecraft 1.20.1 (#16)

`./gradlew -Ploader=forge :forge:build` builds `forge/build/libs/sbwnpc-<version>-mc1.20.1-forge.jar`:
the shared sources compiled for 1.20.1, the Forge entry points (`SquadForgeMod`, `SquadForgeClient`), the SuperbWarfare
adapter (a copy of the NeoForge one against SBW 0.8.9.2 for 1.20.1), the mixins with a refmap, and a Forge channel for
the packets. Java 17 output; Kotlin language and library 2.0, because Kotlin for Forge 4.11 ships the 2.0 runtime.

Checked on a dedicated Forge 1.20.1-47.4.0 server (SBW 0.8.9.2, Kotlin for Forge 4.11.0, SmartBrainLib 1.15): it starts,
every mixin applies (the jar manifest names the config, and the mixins that target SBW use SBW's SRG method names), `/summon sbwnpc:npc` gives an NPC with an SBW rifle and sidearm, and the NPC is still there with its
guns after a restart. Played on a client (Forge 47.4.28) and with two clients on a dedicated Forge server by the owner:
screens, NPC guns in hand, squads and orders, and a second player joining all work. Not checked: long sessions, saving
and reloading, vehicles in depth.

How the 1.21 and 1.20.1 differences are kept out of the shared code: classes whose overridable methods differ
(`NpcEntityBase`, `VersionedSavedData`, `VersionedEntityBlock`, `VersionedBlockEntity`, `VersionedItem`, `ScrollScreen`),
the packet vocabulary (`PacketPayload`, `PayloadCodec`, `Codecs`), the node evaluator (`VehicleAwareNodeEvaluator`),
`StackData` and a handful of small calls (`VersionCompat`) have one file per version with the same names: the 1.21
ones in `common/src/mc121` (NeoForge and Fabric), the 1.20.1 ones in the `forge` module.

Differences and gaps:

- Data in the shared `data/` folder uses 1.21 folder names; the Forge module carries its own `loot_tables`.
- NPCs hold SBW guns in SBW's own arm pose (the M2HB and the minigun have theirs) and draw its static LOD model
  (`SbwClientPorts`), as on NeoForge. The mesh baking is a 1.20.1 copy of the NeoForge one: the vertex and hand-transform calls differ.
- 1.20.1 draws no dimmed or blurred background behind a screen by itself: the mod's screens extend `SquadScreen`, which
  draws the dim background there. There is no blur on 1.20.1.
- The aircraft rendering mixins and `PerfProbe` are not ported.
- SmartBrainLib 1.15 has no door behaviour; a vanilla goal opens doors instead.
- Worlds are not portable between 1.20.1 and 1.21: block positions and item data are saved in each version's format.

Still to do for Forge: aircraft rendering; running the JourneyMap plugin (`integration/journeymap`, a copy of the NeoForge
one against JourneyMap API 1.20.1-2.0.0, compiled only) in JourneyMap; the `enabled` profile and release artifact validation.
JourneyMap 6.0.7 cannot be used on a Legacy Launcher client while `authserver.ely.by` is unreachable: it looks up player
skins on the render thread through `authlib-ely` and the game freezes for 30 s per lookup (seen in thread dumps; not
caused by this mod).

## Local test pack and release

- `python3 scripts/localpack.py <fabric|forge|neoforge> [--mrpack]` builds a mods folder (and with `--mrpack` a
  file Prism Launcher or the Modrinth app imports) from Modrinth: the SuperbWarfare pinned in `gradle/sbw/`, the
  addon's dependencies and quality-of-life mods, plus the built addon jar. The lists are in `scripts/localpack/`.
  Mods the build pins to a version that an unpinned newest release would not load with (Fabric Language Kotlin on
  Fabric loader 0.17.2, Kotlin for Forge 4.11, JEI on NeoForge 21.1.228: newer JEI needs 21.1.238) are listed under `pinned`.
- Jars are named `sbwnpc-<version>-mc<minecraft>-<loader>.jar` on every loader.
- Found and fixed while auditing the Fabric module: `fabric.mod.json` shipped the literal `${version}`;
  `verify-release-artifact.py` rejected a `preview` profile; the Fabric jar had no loader classifier.
  CI builds the Fabric jar. Releases still publish only the enabled NeoForge jar.
