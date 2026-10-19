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

`./gradlew -Ploader=fabric :fabric:build` builds `fabric/build/libs/sbwnpc-<version>-mc1.21.1.jar`:
all 719 classes of `common`, remapped to intermediary, with a no-op entry point. The shared mixin
(`PathNavigationAccessor`) is remapped statically (no refmap).

Build prerequisites found on the way:

- **Gradle 9.5.** The pinned ReFabricated jar was built with Loom 1.17.20; Loom refuses mods built
  by a newer Loom than its own, and 1.17 needs Gradle 9.5. The wrapper and the headless script
  moved from 8.14.2 to 9.5.0. The NeoForge build, `detekt`, ktlint, unit tests and GameTests pass on
  it. The headless script's checksum was computed from the copy served by the wrapper's mirror;
  compare it with Gradle's published value for 9.5.0.
- **Parchment is not used on Fabric.** `maven.parchmentmc.org` redirects to `ldtteam.jfrog.io`,
  which the cloud network policy does not allow. Parchment only adds parameter names, so the
  bytecode is the same; add the host and `loom.layered { parchment(...) }` to get them.
- **Maven Central rate limits (HTTP 429)** the shared cloud proxy; retrying later works.

Not done, in this order:

1. **Entry point and registration**: entities, items, blocks, block entities, sensors and
   memories (`init/` is NeoForge `DeferredRegister`), the `Ports` wiring, `Settings.current`.
2. **Loader seams**: `LoaderHooks` (grief rule, persistent entity data, ladders), the clientbound
   packet transport, lifecycle and tick events, config.
3. **SBW adapter** (`integration/sbw`, about 2,900 lines) against ReFabricated; player supply moves
   from NeoForge attachments to Cardinal Components. Mixins that target SBW.
4. **Client**: screens, HUD, key mappings, NPC rendering, aircraft world-render bridge, JourneyMap.
5. **CI and release**: a Fabric job, `enabled` profile, artifact validation.

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
