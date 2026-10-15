# Multi-loader repository design

Design for #14; owner approval is recorded in that issue.
This document does not claim that Fabric or Forge support already works.

## Baseline and release lines

Inspected develop at 6f1ad23606fd012a43ee1c49e0f765e2b01a6a26.
Publish this document through develop.
Ports #15 and #16 start from v0.6.0 (3256b36edafd6813296457e7a0a6339cac54ee70).
Their structural implementation branches start from that tag, as those issues require.
Carry the structural changes into develop separately, without importing later features
into the v0.6.0 ports. Share code per release line, never fork gameplay per loader.
NeoForge stays on Minecraft 1.21.1 / Java 21; Fabric targets Minecraft 1.21.1.
The owner must choose Forge's Minecraft and SBW versions in #16 before implementation.
A different Minecraft version requires a separate API/version compatibility assessment.

## Decision and build structure

Use common, neoforge, fabric and forge Gradle modules, with build-logic for conventions.
Initially enable common + neoforge only; enable other loaders after their compatibility audits.
common owns shared sources/resources and is checked against unpatched Minecraft APIs.
Each loader compiles those shared sources with its own toolchain and packages them once.
Use Mojang names for the 1.21.1 baseline; Fabric's production jar requires Loom remapping.
Keep ModDevGradle for NeoForge, use Fabric Loom for Fabric, and choose Forge tooling
after its target is agreed. Prove Kotlin, mappings, resources, mixins and development
runs in a small build spike before migrating the full tree. Pin compatible tool versions.

    build-logic/             shared Gradle conventions
    common/src/main/         gameplay, contracts, assets and data
    common/src/client/       shared client sources, isolated from server code
    neoforge/src/            NeoForge hooks, SBW adapter, metadata and mixins
    fabric/src/              Fabric hooks and ReFabricated adapter
    forge/src/               Forge hooks and its SBW adapter after target agreement
    gradle/                  pinned dependency/toolchain profiles
    docs/                    designs and compatibility reports

Choose the common-source MultiLoader pattern without adding Architectury API at runtime.
A conditional single-module build mixes incompatible toolchains and permits dependency leaks.
Architectury API is an alternative only if the build spike identifies a concrete unmet need.
This is a project design decision, not a claim that a template proves SBW compatibility.

## Source ownership

| Existing code | Destination and required extraction |
| --- | --- |
| AI, tactics, combat, routes, squad/faction rules | common; extract event hooks and loader calls first |
| NPC entity, blocks and items | common vanilla implementation; isolate loader-added methods and swim attributes |
| SquadManager, PlayerFactionRegistry | common vanilla SavedData; preserve IDs and NBT schemas |
| domain/port | common SBW contracts; client contracts in client sources |
| integration/sbw | owning loader module; implementations need not match across forks |
| SquadMod, init registries | loader entrypoints/registration; shared code uses vanilla handles or suppliers |
| ServerTick, ServerLifecycle, entity indexes | common handlers called by loader-specific events |
| Payloads, snapshots, gameplay packet handlers | common after removing annotated bootstrap dependencies |
| ModNetwork, PacketDistributor | loader registration/transport, thread scheduling and disconnect hooks |
| config | common settings view; loader-owned parsing, reload and server synchronization |
| client screens/renderers/HUD | common client logic; loader-owned input, render and registration hooks |
| JourneyMap | shared map logic where possible; loader-specific dependencies, event and packet hooks |
| assets, data, generated resources | shared when formats match; divergent resources and metadata stay loader-owned |
| mixins | audit individually; SBW targets adapter-owned, vanilla-only candidates potentially shared |

Audit fully qualified calls and overridden loader methods as well as imports.
Move mod ID, resource-location and logging helpers out of the annotated entrypoint.
Preserve existing registry IDs, save data, packet formats and gameplay during extraction.
Do not assume the tactical feature branch's code/tests exist on the v0.6.0 baseline.

## Platform and SBW boundaries

Keep Guns, Vehicles, Mortars, Grenades, Drones, Gear and PlayerSupply semantics in domain/port.
Vanilla Entity and ItemStack types are allowed: common is not Minecraft-version-independent.
Keep GunPoses/GunRendering and their bootstrap strictly client-only.
Install mandatory ports before gameplay and fail startup clearly if an adapter is missing.
Introduce narrow platform contracts only for actual shared needs, such as packet transport
and settings. Loader events call common handlers; avoid a universal event/registry framework.
SbwPlayerSupply's getData(ModDataAttachments.PLAYER_VARIABLE) stays inside its adapter;
Fabric must use its fork's actual storage while preserving the PlayerSupply contract.
SBW event bridges and mixin targets likewise belong to the relevant adapter.

## Dependency selection and compatibility gates

Replace unconditional root includeBuild("SuperbWarfare") with selected-loader profiles.
Record repository, immutable commit or artifact version/checksum, Minecraft and mappings.
Allow an explicit local override for development and report it in build output.
Release CI uses clean pinned inputs and rejects missing/mismatched profiles.
Isolate composite builds/substitution by selected loader so one fork cannot satisfy another.
Use a published SBW artifact only if its API suffices; otherwise build pinned upstream source.
Do not bundle SBW. Resolve SmartBrainLib, Kotlin runtime, GeckoLib, SimpleBedrockModel
and optional JourneyMap separately for each loader; availability is not assumed.

Before #15/#16 implementation, record a compatibility matrix: required port/mixin behaviour,
upstream symbol, adaptation, test and blocking gap. Cover guns/ammo/ballistics, projectiles,
damage/shoot events, vehicles/seats, mortars, drones, supply and client gun rendering.
Essential missing APIs block the port pending implementation or owner-approved scope changes;
never silently substitute a no-op. Forge's unknown version is an explicit open decision.

## Versioning and verification

Use a shared gameplay version for equivalent builds and separate artifacts:
sbwnpc-<version>-mc<minecraft>-<loader>.jar.
Use new prerelease/patch versions for changed releases; never replace existing v0.6.0 bytes.
Port release notes identify the v0.6.0 baseline. Declare verified dependency ranges per loader.
PR CI checks common boundaries/tests and builds every enabled loader's distributable.
Keep Minecraft/bootstrap-dependent tests in loader harnesses, not plain JVM tests.
Extend SbwBoundaryTest across Kotlin/Java and modules; enforce no SBW/loader leakage in common.
Validate jar metadata, resources, mixin application, debug flags, dedicated-server startup
and startup without JourneyMap. Run available GameTests per loader and report missing coverage.
Human client/server and multiplayer checks remain gates for changed behaviour.
Initially NeoForge is the required loader job; enable Fabric/Forge jobs with their ports.
Release CI emits only validated targets, dependency provenance and artifact checksums.
Publishing remains subject to the repository's explicit owner-approval rule.

## Follow-up issues

Create these linked to #14 after owner agreement. Headings are issue titles; paragraphs
are their proposed bodies. #15/#16 remain the port tasks and must not be duplicated.

### Introduce common and neoforge modules from v0.6.0

The single module exposes all sources to NeoForge. Starting from v0.6.0, prove shared-source
Kotlin compilation, vanilla common checking, mappings and NeoForge packaging/development runs.
Then migrate sources in coordination with boundary extraction. Acceptance: existing tests pass,
a real NeoForge jar includes shared classes/resources once, IDs/saves/packets are preserved,
and a documented human smoke test passes. Bring the structural changes into develop separately.
Depends on #14 and coordinates with the boundary extraction issue.

### Separate loader hooks and SBW adapters from common gameplay

Inventory boundaries from this design, including NpcEntity's qualified NeoForge calls,
registration holders, config, networking, lifecycle and client bootstrap. Extract shared handlers
and narrow platform contracts, keeping SBW implementation/mixins in the loader module.
Acceptance: common compiles against vanilla APIs, server startup works, Kotlin/Java boundary
checks pass, and persistence/network/combat/map contracts retain their existing behaviour.
Coordinate with module migration; both must finish before #15/#16 reuse common.

### Pin SBW inputs and audit port compatibility

The unconditional composite build accepts any local SBW checkout. Add isolated loader profiles,
immutable input revisions, explicit development overrides and dependency provenance.
Acceptance: a clean NeoForge build reproduces its inputs, wrong-loader/missing inputs fail,
and #15/#16 receive API/mixin/dependency compatibility reports before implementation.
Forge target selection must be agreed with the owner before its audit.

### Add CI checks and release validation for enabled loaders

Add boundary checks, common tests, enabled-loader builds/server smoke checks and artifact
metadata/resource/debug-flag validation with pinned dependency provenance.
Acceptance: NeoForge is required initially, Fabric/Forge become required when implemented,
disabled targets produce no release artifact, and manual playtest gates are documented.
Depends on module/boundary extraction and pinned SBW input profiles.

#14 is complete after owner agreement, merging the design into develop, and creating/linking
the follow-ups. Implementation of the modules and ports belongs to the follow-up tasks.

## References

- [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template)
- [ModDevGradle and vanilla mode](https://github.com/neoforged/ModDevGradle)
- [Fabric Loom](https://docs.fabricmc.net/develop/loom/)
