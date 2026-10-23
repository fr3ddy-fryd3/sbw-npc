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
- **Only the loader chosen with `-Ploader=<neoforge|fabric>` is configured** (default `neoforge`).
  A NeoForge build never needs Loom or the Fabric Maven.

## Fabric (#15)

`./gradlew -Ploader=fabric :fabric:build` builds `fabric/build/libs/sbwnpc-<version>-mc1.21.1-fabric.jar`:
the shared sources, the Fabric entry points (`SquadFabricMod`, `SquadFabricClient`), the SuperbWarfare adapter
(`integration/sbw`, a copy of the NeoForge one with ReFabricated's registries and player data) and the mixins.

Checked on a dedicated Fabric server (Fabric Loader 0.17.2, ReFabricated 0.8.9.2, the local pack's mods): it starts,
SBW and the addon initialise, and `/summon sbwnpc:npc` gives an NPC with an SBW rifle and sidearm that stays alive.
Not checked: a client (screens, HUD, rendering, key bindings), players, deploying and commanding squads, combat,
vehicles, saving and reloading. `./gradlew runServer` cannot be used for this: ReFabricated reflects on
intermediary class names and fails in a development environment, so test with the built jar.

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

`common` was compiled against 1.20.1 with Mojang names (Java 17 output, SmartBrainLib 1.15):

- **441 errors, in 33 of about 229 files.** No JDK 21-only API is used.
- `network/Payloads.kt`: 222. It is built on `CustomPacketPayload`, `StreamCodec` and
  `RegistryFriendlyByteBuf`, which 1.20.1 does not have. This one file is rewritten for 1.20.1
  (a `FriendlyByteBuf` codec per packet).
- `entity/ai/VehicleAwareNavigation.kt` 53, `VehicleRoutes.kt` 13 (`PathType`,
  `PathfindingContext`), `entity/NpcEntity.kt` 28 (save/load signatures, pathing), `squad/*`
  about 40 (`SavedData` factory and `HolderLookup.Provider`), `block/*` about 30 (block codecs,
  `useWithoutItem`, `DimensionTransition`), the rest 1-9 each (`ResourceLocation.fromNamespaceAndPath`,
  `registryAccess`, `bottomCenter`).

Approach: move each differing call behind a small interface in `common`, with the 1.21 version as
the shared implementation and the 1.20.1 version in the Forge module. Entity and `SavedData`
overrides whose signatures differ (`saveAdditional`, `save`) cannot be bridged by an interface;
they delegate to a shared function from a thin per-version override.

Further Forge work, none started: the `forge` module (ForgeGradle, reobfuscation, KotlinForForge
and Kotlin version agreed with SBW's, JourneyMap on Forge 47.x), the SBW adapter against SBW
0.8.9.2 for 1.20.1 (NBT instead of data components, capabilities instead of attachments), the
mixins, and the client. Forge is larger than Fabric by roughly the 441 errors above plus a second
SBW adapter.

## Local test pack and release

- `python3 scripts/localpack.py <fabric|forge|neoforge> [--mrpack]` builds a mods folder (and with `--mrpack` a
  file Prism Launcher or the Modrinth app imports) from Modrinth: the SuperbWarfare pinned in `gradle/sbw/`, the
  addon's dependencies and quality-of-life mods, plus the built addon jar. The lists are in `scripts/localpack/`.
  Mods the build pins to a version that an unpinned newest release would not load with (Fabric Language Kotlin on
  Fabric loader 0.17.2, Kotlin for Forge 4.11) are listed under `pinned`.
- Jars are named `sbwnpc-<version>-mc<minecraft>-<loader>.jar` on every loader.
- Found and fixed while auditing the Fabric module: `fabric.mod.json` shipped the literal `${version}`;
  `verify-release-artifact.py` rejected a `preview` profile; the Fabric jar had no loader classifier.
  CI builds the Fabric jar. Releases still publish only the enabled NeoForge jar.
- Known and not fixed: the Forge module does not compile (above), so its jar, reobfuscation and the mixin config
  added for it (`sbwnpc.forge.mixins.json`, needs a refmap check) are untested.
