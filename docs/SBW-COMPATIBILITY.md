# Pinned SBW inputs and compatibility audit

Issue #19. Addon baseline: v0.6.0 (3256b36edafd6813296457e7a0a6339cac54ee70).
The inspected develop gameplay sources match this baseline; later tactical work is excluded.
Audit date: 2026-10-15. Checks below distinguish binary/source inspection from execution.

## Selected inputs and build behaviour

| Loader | Selected input | State |
| --- | --- | --- |
| NeoForge / MC 1.21.1 | Modrinth Cd3DYqzn / bl7W8fjU, SBW 0.8.9.2 | Enabled; build/tests still required |
| Fabric / MC 1.21.1 | Modrinth XU8zWdjt / OsEZFHcH, ReFabricated 1.21.1-0.8.9.2 | Audited, addon build disabled pending #17/#18/#15 |
| Forge | Minecraft/SBW versions not selected by owner | No guessed coordinate or compatibility claim; #16 remains gated |

Version IDs, repositories, source references and independently checked SHA-512 values
are in gradle/sbw/. Source references: NeoForge c22d98c232aeb8f6150799e16387ffa09b488681;
Fabric e8e48e10e112e9c65520a132af27746e31e92831. Fabric's source reference is an audit
reference, not an assertion that rebuilding that commit reproduces the published bytes.
The selected binary and its checksum are authoritative for normal builds.

Normal ./gradlew build/test uses the published NeoForge artifact, without cloning SBW.
verifySbwInput checks its SHA-512 before compilation, tests, jars and game runs and
writes build/reports/sbw/input.txt. A changed byte invalidates the task input and must fail.
The addon does not bundle SBW. Embedded dependencies inside the selected SBW jar,
including its Rhino SNAPSHOT, are covered by the outer jar checksum.
This pins SBW inputs, not every unrelated Maven dependency or the whole build toolchain.

-PsbwSourceDir=/absolute/path explicitly enables a development composite build.
The directory must be a Git root, identify SuperbWarfare/MC 1.21.1, and use NeoForge.
Actual revision, origin, dirty state and mod version are reported. This mode deliberately
allows local development changes and is rejected in CI; it is not a pinned release input.
Selecting an unknown/missing/disabled loader profile fails rather than falling back to NeoForge.
Cloud warm-up resolves the pinned artifact and no longer clones a moving SBW branch.

## What was verified before implementation

Both published jars were read in memory and independently matched their Modrinth SHA-512.
All 51 directly referenced SBW class/interface names exist in both binaries, including
VehicleModelEntry (declared in another source file). Checked classes use Java 21 bytecode.
Inspected key descriptors for GunData, ShootParameters usage, vehicle firing/geometry,
mortar targeting, supply attachments, model reload and rendering.
Fabric Minecraft descriptors use intermediary names and require Loom remapping.
No addon compile, JUnit, GameTest, server or client playtest has passed as part of this audit.

The Modrinth source link still points at the old 0.8.8 Fabric branch. That branch has
substantial missing/different APIs and is not the selected port input. Its Java-only
ShootParameters and two-argument mortar firing must not be attributed to selected 0.8.9.2.

## Gameplay and integration matrix for #15

| Contract / caller | Selected Fabric API and required work | Verification required |
| --- | --- | --- |
| Guns: SbwGuns | GunData.from/tick/reloadAmmo/canShoot/shoot/save and ammunition fields exist; adapt direct ModItems values instead of NeoForge suppliers | Compile; ammo/reload/burst/point-target/offscreen-hit tests |
| Vehicles: SbwVehicles | VehicleEntity seats, canShoot, vehicleShoot, energy and geometry APIs exist; isolate registry and loader-added APIs | Compile; seats, armour, steering, power and rotorcraft playtests |
| Mortars: SbwMortars | MortarEntity.setTarget and vehicleShoot(LivingEntity,String,Vec3) exist; FiringParametersItem/TrajectoryCalculator exist | Compile; reachability, lay/fire and shell handling |
| Grenades: SbwGrenades | HandGrenadeEntity/RgoGrenadeEntity/ExplosionConfig/RangeTool exist; replace loader explosion hooks | Compile; fuse, collision and friendly-fire checks |
| Drones: SbwDrones | DroneEntity/SwarmDroneEntity/DRONE_ATTACHMENT/CustomExplosion exist; adapt registry access | Compile; controller, inputs, damage and fallback without optional Drone Warfare |
| Gear: SbwGear | MedicalKitItem.treat exists; ModItems uses direct Fabric values | Compile; uniforms, healing and projectile damage tags |
| PlayerSupply: SbwPlayerSupply | ModAttachments.PLAYER_VARIABLE is a CCA ComponentKey, not NeoForge Supplier; use native component access/sync | Compile; ammo changes, respawn and multiplayer sync |
| GunPoses / GunRendering | PoseTool/GunItem and SBW rendering classes exist; use the Fabric SimpleBedrockModel fork | Model decode tests; resource reload and third-person gun rendering |
| Hearing / creative tabs / boarding | ShootEvent is not a NeoForge event; ModItems/ModEntities use Fabric registration; FakePlayer and event subscribers are loader-specific | Native event delivery; ally boarding and duplicate-shot regression checks |
| Aircraft snapshots / rendering | BVR/model classes exist; SyncedEntityWorldRenderer has private render(WorldRenderContext), not onRenderLevelStage | Rewrite world-render bridge; BVR, LOD and multiplayer visual checks |

Class presence alone does not establish binary or behavioural compatibility.
The shared gameplay ports can remain stable, while these adapters must be compiled
and tested against the selected loader. No missing feature may become a silent no-op.

## Every current mixin

| Mixin | Target / Fabric requirement | Verification required |
| --- | --- | --- |
| VehicleGunDataMixin | GunData.shoot(ShootParameters) exists; selected Kotlin data class supports copy; inspect spread injection | Projectile-spread regression |
| AutoAimableEntityMixin | autoAim exists; verify its exact inherited three-argument vehicleShoot invocation after remap | Autonomous drone-target accuracy |
| VehicleShootingMixin | VehicleEntity.canShoot(LivingEntity) exists; remap Minecraft descriptor | Friendly-fire gate |
| VehicleBoardingMixin | VehicleEntity interaction uses a mapped vanilla method; port FakePlayer/tool/registry handling | Allied/full-seat boarding |
| AircraftSnapshotMixin | buildBvrSyncNbt exists; vanilla readAdditionalSaveData is mapped on Fabric | BVR save/load and sync |
| AircraftModelEntriesMixin | getModelEntries exists | Aircraft model/LOD selection |
| AircraftModelReloadMixin | apply(Map,ResourceManager,ProfilerFiller) exists; remap descriptor and reload hook | Model resource reload |
| AircraftRendererMixin | getCurrentModelEntry and shouldRender exist; remap Minecraft descriptors | Frustum/LOD correctness |
| AircraftWorldRenderMixin | onRenderLevelStage is absent; bridge private render(WorldRenderContext)/Fabric callback instead | No duplicate/missing distant aircraft |
| EntityAllianceMixin | Vanilla Entity.isAlliedTo; Loom mapping/refmap required | Faction/alliance behaviour |
| EntityTrackingRangeMixin | Vanilla EntityType.clientTrackingRange; Loom mapping/refmap required | Entity tracking near/far |
| KeyMappingMixin | Vanilla KeyMapping.consumeClick; client-only mapping/bootstrap required | Tool/map input |

Existing remap=false SBW mixins cannot be assumed portable: their descriptors still
contain Minecraft types and some targets are overridden vanilla methods.

## Other dependencies

| Dependency | NeoForge baseline | Fabric candidate / evidence |
| --- | --- | --- |
| Kotlin language provider | KotlinForForge 5.10.0 | fabric-language-kotlin 1.13.2+kotlin.2.1.20; Maven metadata checked |
| SmartBrainLib | 1.16.11 / NeoForge 1.21.1 | Same version's Fabric 1.21.1 POM exists |
| GeckoLib | 4.7.5, nested in selected SBW | Fabric 1.21.1 4.7.5 POM exists; required separately by Fabric SBW |
| SimpleBedrockModel | 2.5.1.1-neoforge-mc1.21.1, nested in SBW | Sh1roCu/SimpleBedrockModel-Fabric at de0caee7f9656676e6de0859077fb61103007a9e, nested in Fabric SBW |
| JourneyMap API | 2.0.0-1.21.1 / NeoForge | Fabric API POM at the same version exists; optional integration still needs runtime checks |

Fabric SBW additionally declares Fabric Loader >=0.17.2, Fabric API, Cloth Config,
ForgeConfigAPI Port, ExtensibleEnums, Trinkets and ModernKeyBinding. Its jar includes
ModernKeyBinding, Ponder, SimpleBedrockModel, Cardinal Components, Energy, ExtensibleEnums
and Rhino; do not copy NeoForge dependencies into Fabric or bundle them again blindly.
The checksum pins bundled bytes; exact external runtime versions and model API compatibility
must be verified by #15's build and client/server tests before enabling the loader.

## Forge decision gate for #16

Owner must select Minecraft version and an actual Forge SBW artifact/source revision.
Then repeat the class/member, mixin, mappings, Kotlin provider and dependency inventory
against those exact inputs. Different Minecraft versions also require a common-source
compatibility assessment. No Forge audit was run without that decision.

## Reproduction and acceptance

After approval and applying this patch:
- Run ./gradlew test build verifySbwInput with no local override; inspect input.txt.
- Check failures for unknown loader, Fabric/Forge selection and missing source override.
- Check a development source override records its actual Git revision/dirty state.
- Check CI rejects source overrides and an incorrect artifact checksum fails verification.
- Record actual build/test outcomes in #19; source/binary inspection is not a passed build.
- Supply this report to #15/#16. Forge remains pending the explicit target decision.

Sources: [NeoForge release](https://modrinth.com/mod/superb-warfare/version/bl7W8fjU),
[Fabric release](https://modrinth.com/mod/superb-warfare-refabricated/version/OsEZFHcH),
[NeoForge source](https://github.com/Mercurows/SuperbWarfare/tree/c22d98c232aeb8f6150799e16387ffa09b488681),
[Fabric source](https://github.com/CrawKatt/SuperbWarfareReFabricated/tree/e8e48e10e112e9c65520a132af27746e31e92831).
