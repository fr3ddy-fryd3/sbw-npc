# Project map

A guide to directories, modules and entry points. How tactics work: [AI-RUNTIME.md](AI-RUNTIME.md)
and [TACTICS-ARCHITECTURE.md](TACTICS-ARCHITECTURE.md); code style: [CODE-STYLE.md](CODE-STYLE.md).

## Where to start

The code is split into two modules. `common/` holds loader-independent code: the NPC entity and
its behaviours, squads, combat and tactics, the HTN core (`ai/planning`, `ai/coordination`,
`ai/tactics`) and its `ai/integration` adapter, long routes, NPC descriptions (`npc/`), network
payloads, the SuperbWarfare ports (`domain/port/`) and experience rules. `neoforge/` holds what is
bound to NeoForge: the entry point and registration, event subscribers, the SBW adapter, the
network transport, config and client code. Most of the code
is Kotlin; Java is used for mixins into Minecraft and SuperbWarfare.

| Path from the repository root | Contents |
|---|---|
| [neoforge/src/main/kotlin/com/sbwnpc/squad/](../neoforge/src/main/kotlin/com/sbwnpc/squad/) | NeoForge entry point, registration, events, SBW adapter, network, config and client |
| [common/src/main/kotlin/com/sbwnpc/squad/](../common/src/main/kotlin/com/sbwnpc/squad/) | Loader-independent code, gameplay included |
| [neoforge/src/main/java/com/sbwnpc/squad/mixin/](../neoforge/src/main/java/com/sbwnpc/squad/mixin/), [common/src/main/java/com/sbwnpc/squad/mixin/](../common/src/main/java/com/sbwnpc/squad/mixin/) | Mixins into Minecraft and SuperbWarfare classes; the loader-independent accessors in common |
| [neoforge/src/main/resources/](../neoforge/src/main/resources/), [common/src/main/resources/](../common/src/main/resources/) | Textures, models, translations, metadata and the mixin list |
| [neoforge/src/test/kotlin/com/sbwnpc/squad/](../neoforge/src/test/kotlin/com/sbwnpc/squad/), [common/src/test/kotlin/com/sbwnpc/squad/](../common/src/test/kotlin/com/sbwnpc/squad/) | Automatic tests, laid out like the main code |
| [gradle.properties](../gradle.properties) | Versions of the mod, Minecraft and dependencies |
| [build.gradle.kts](../build.gradle.kts), [neoforge/build.gradle.kts](../neoforge/build.gradle.kts), [common/build.gradle.kts](../common/build.gradle.kts) | Root tasks; the NeoForge build, scenes and ktlint; the common module's checks and `BuildFlags` |
| [settings.gradle.kts](../settings.gradle.kts) | The `common` and `neoforge` modules, `build-logic` and the SuperbWarfare input |
| `SuperbWarfare/` | A separate local checkout of the dependency; not part of this repository |
| `build/` | Build output, test reports and generated files |
| [PLAN-1.0.0.md](../PLAN-1.0.0.md) | The release plan; intentions, not proof of implementation |
| [AGENTS.md](../AGENTS.md) | How to work on the repository, and technical notes |

## Main modules

| Module | Responsible for | Entry points |
|---|---|---|
| Initialisation | Wiring ports, registrations and configs | [SquadMod.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/SquadMod.kt), [init/](../neoforge/src/main/kotlin/com/sbwnpc/squad/init/) |
| Server events | Shared updates and clearing runtime data between worlds | [ServerTick.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/ServerTick.kt), [ServerLifecycle.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/ServerLifecycle.kt) |
| NPC | The entity, its Brain, state, pose, recovery when stuck | [entity/NpcEntity.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/NpcEntity.kt), [entity/NpcStuckRecovery.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/NpcStuckRecovery.kt) |
| Individual AI | Shooting, cover, healing, supply, movement, vehicles | [entity/ai/](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/) |
| Combat | Shared computations of sight, threats, fire, formations and safe positions | [combat/](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/) |
| Squad tactics | The HTN core: tasks, operations, attack phases and roles, members' duties | [common/.../ai/](../common/src/main/kotlin/com/sbwnpc/squad/ai/), adapter [ai/integration/](../neoforge/src/main/kotlin/com/sbwnpc/squad/ai/integration/) |
| Tactics execution | Squad snapshot, position search, routes, distances | [combat/tactics/](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/), see [TACTICS-ARCHITECTURE.md](TACTICS-ARCHITECTURE.md) |
| Squads and orders | Members, owner, orders, selection, deployment, saving | [squad/Squad.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/Squad.kt), [squad/SquadManager.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/SquadManager.kt) |
| Experience | Awards, contribution to a kill, combat wounds, rank thresholds | [experience/](../neoforge/src/main/kotlin/com/sbwnpc/squad/experience/), rules in [common/.../experience/](../common/src/main/kotlin/com/sbwnpc/squad/experience/) |
| NPC descriptions | Classes, ranks, presets, factions and vehicle models | [common/.../npc/](../common/src/main/kotlin/com/sbwnpc/squad/npc/) |
| Factions and diplomacy | Scoreboard teams, hostility, alliances | [team/](../neoforge/src/main/kotlin/com/sbwnpc/squad/team/), [squad/PlayerFactionRegistry.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/PlayerFactionRegistry.kt) |
| Long routes | Planning movement across terrain and its computation limits | [route/](../common/src/main/kotlin/com/sbwnpc/squad/route/) |
| Vehicles | Water, airspace, helicopters, fuel and driver ownership | [vehicle/](../neoforge/src/main/kotlin/com/sbwnpc/squad/vehicle/) |
| Barracks and Supply | Blocks, the reinforcement queue, kits and respawn | [block/](../neoforge/src/main/kotlin/com/sbwnpc/squad/block/), [block/entity/](../neoforge/src/main/kotlin/com/sbwnpc/squad/block/entity/) |
| Squad tool | Using the tool, its configuration and handing it to the player | [item/SquadToolItem.kt](../common/src/main/kotlin/com/sbwnpc/squad/item/SquadToolItem.kt) |
| Network | Client ↔ server messages and the server's handling of actions | [network/Payloads.kt](../common/src/main/kotlin/com/sbwnpc/squad/network/Payloads.kt), [network/ModNetwork.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/network/ModNetwork.kt) |
| Interface | Screens, HUD, keys, client state snapshots, NPC rendering | [client/](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/), [client/screen/](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/screen/) |
| Map | Server data feed, client state, JourneyMap | [map/MapFeed.kt](../common/src/main/kotlin/com/sbwnpc/squad/map/MapFeed.kt), [integration/journeymap/](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/journeymap/) |
| SBW contracts | Interfaces for weapons, vehicles, grenades, gear and their wiring | [domain/port/](../common/src/main/kotlin/com/sbwnpc/squad/domain/port/) |
| SBW integration | Calls into SuperbWarfare's concrete classes and API | [integration/sbw/](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/) |
| Settings and diagnostics | Configs, log groups, load measurement | [config/](../neoforge/src/main/kotlin/com/sbwnpc/squad/config/), [combat/DebugFlags.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/DebugFlags.kt), [util/PerfProbe.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/util/PerfProbe.kt) |

## Where to change a mechanic

### NPC behaviour and combat

In [NpcEntity.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/NpcEntity.kt) look for
`getSensors`, `getCoreTasks`, `getIdleTasks`, `getFightTasks`: that is where behaviours are attached
to the Brain. The implementations are in `entity/ai/`; shared computations are in `combat/`.

| What to change | Where to look |
|---|---|
| Choosing and detecting an enemy | [SquadTargetSensor.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/SquadTargetSensor.kt), [DetectionSightline.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/DetectionSightline.kt), [Vision.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/Vision.kt) |
| Choosing a known target that can be shot | [TacticalTargets.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalTargets.kt), [TacticalTargetSelection.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalTargetSelection.kt), [FiringOpportunity.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/FiringOpportunity.kt) |
| Rays for shooting and cover | [Sightline.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/Sightline.kt); compare with the separate `DetectionSightline` before changing it |
| Shooting, ranges, bursts, reloading | [GunAttackBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/GunAttackBehaviour.kt), [AutomaticBurst.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/AutomaticBurst.kt), [OpeningFire.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/OpeningFire.kt), [npc/NpcClass.kt](../common/src/main/kotlin/com/sbwnpc/squad/npc/NpcClass.kt) |
| Calls into the SBW weapon API | [integration/sbw/SbwGuns.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/SbwGuns.kt), [domain/port/Guns.kt](../common/src/main/kotlin/com/sbwnpc/squad/domain/port/Guns.kt) |
| Taking cover and leaving it to shoot | [SeekCoverBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/SeekCoverBehaviour.kt), [CoverSearch.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/CoverSearch.kt), [CombatPosition.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/CombatPosition.kt) |
| Losing an enemy in grass, remembering the spot, limited bursts | [LastSeenFire.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/LastSeenFire.kt), carried out by [BlindReturnFireBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/BlindReturnFireBehaviour.kt) |
| Reacting to hits and an unknown shooter | [IncomingFire.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/IncomingFire.kt), [BlindReturnFireBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/BlindReturnFireBehaviour.kt), [SuppressionEvents.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/SuppressionEvents.kt) |
| Hearing noises and alarms to allies | [Hearing.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/Hearing.kt), [Alarm.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/Alarm.kt), [DeathSites.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/DeathSites.kt), `NpcEntity.hear` / `alert` |
| Aiming the weapon independently of the body | [ShotAim.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/ShotAim.kt), called in `GunAttackBehaviour.tickShooting`, adapter `SbwGuns` |
| Safe fire near allies | [FriendlyFireGuard.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/FriendlyFireGuard.kt), [FiringCone.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/FiringCone.kt), [SbwProjectileSafety.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/SbwProjectileSafety.kt), [FireAllocation.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/FireAllocation.kt) |
| Spreading shooters over posts | [FiringSpots.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/FiringSpots.kt), `TacticalPositions.kt`, `CoverSearch.kt` |
| A position search's aim, and occupying a firing post exactly | [TacticalAim.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalAim.kt), [FiringPostArrival.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/FiringPostArrival.kt) |
| Continuing the search for a blocked firing post | [TacticalFirePosts.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalFirePosts.kt), [TacticalTask.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalTask.kt), [NativePositionAction.kt](../common/src/main/kotlin/com/sbwnpc/squad/ai/integration/NativePositionAction.kt) |
| Ray queues and native A* | [TickBudget.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/TickBudget.kt), [TacticalBudget.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalBudget.kt); carrying on between queues in `TacticalPositions.kt` / `TacticalTask.kt` |
| A combat position's distance from known enemies, route safety | [CombatPositionSafety.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/CombatPositionSafety.kt), [TacticalClearance.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalClearance.kt) |
| Preferring cover to an open position | [TacticalPositionScore.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalPositionScore.kt), `TacticalPositions.kt`; the individual search is `GunAttackBehaviour.bestFiringSpot` |
| Healing | [MedicHealBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/MedicHealBehaviour.kt), [SelfTreatBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/SelfTreatBehaviour.kt) |
| Grenades and dodging them | [GrenadeUseBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/GrenadeUseBehaviour.kt), [GrenadeThrowBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/GrenadeThrowBehaviour.kt), [GrenadeEvadeBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/GrenadeEvadeBehaviour.kt) |
| Squad tactics | The HTN controller decides in [ai/integration/NativeTactics.kt](../common/src/main/kotlin/com/sbwnpc/squad/ai/integration/NativeTactics.kt); operations, phases and roles are in [ai/tactics/](../common/src/main/kotlin/com/sbwnpc/squad/ai/tactics/); one assignment is carried out by [NativePositionAction.kt](../common/src/main/kotlin/com/sbwnpc/squad/ai/integration/NativePositionAction.kt) |
| A sniper's or machine gunner's protected high post | [DefensiveOverwatch.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/DefensiveOverwatch.kt), [TacticalPositions.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalPositions.kt) |

### Orders, movement and vehicles

`GunAttackBehaviour.tick` shows the order of a combat update: order change → observation and aim →
movement → firing lane → weapon work and bursts. The steps are in `updateAim`,
`updateCombatMovement`, `updateFiringLane`, `clearBlockedFiringLane` and `tickShooting`.
`holdOrderedPosition` holds the position and returns the NPC to its defence area.

`VehicleTransportBehaviour.tickDriving` shows the order of driving. Fuel is checked in
`stopIfPowerExhausted`, progress in `updateProgressAndCheckStall`, getting stuck in
`recoverIfStuck`, an ally in front of the vehicle in `yieldToBlockingAlly`. `steerBoat` follows a
water route; land waypoints come from `currentWaypoint` and `driveWaypoint`.

`route/` plans routes across the world. `squad/RouteManager.kt` keeps **patrol routes recorded by the
player**. These are different things despite the similar names.

| What to change | Where to look |
|---|---|
| Orders available per squad class | [squad/SquadOrder.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/SquadOrder.kt), `availableFor`; used by the screens, HUD, JourneyMap and the manager |
| Infantry carrying out an order | [SquadOrderBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/SquadOrderBehaviour.kt) |
| Long march, waypoints and progress | [SquadMarch.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/SquadMarch.kt), [MarchProgress.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/MarchProgress.kt), [GroundRouteProgress.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/GroundRouteProgress.kt), [route/Walking.kt](../common/src/main/kotlin/com/sbwnpc/squad/route/Walking.kt) |
| Short paths and an NPC getting stuck | [VehicleAwareNavigation.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleAwareNavigation.kt), [WalkingClearance.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/WalkingClearance.kt), [NpcStuckRecovery.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/NpcStuckRecovery.kt) |
| Remembering steps that can't be walked, and a deferred jump cancel | [FailedWalkingSteps.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/FailedWalkingSteps.kt), `VehicleAwareNavigation.rejectStuckStep` / `stopAfterLanding` |
| Boarding, leaving and riding a car | [VehicleTransportBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleTransportBehaviour.kt), [VehicleRoutes.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleRoutes.kt), [GroundTravelProgress.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/GroundTravelProgress.kt) |
| Driving over land and across water | [route/Driving.kt](../common/src/main/kotlin/com/sbwnpc/squad/route/Driving.kt), [route/GroundMap.kt](../common/src/main/kotlin/com/sbwnpc/squad/route/GroundMap.kt), [vehicle/WaterRoutes.kt](../common/src/main/kotlin/com/sbwnpc/squad/vehicle/WaterRoutes.kt), [BoatTrips.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/BoatTrips.kt) |
| Tanks and crew weapons | [VehicleCrewBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleCrewBehaviour.kt), [TankWeaponSelection.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/TankWeaponSelection.kt) |
| Helicopters: orders, flight, attack orbit | [HelicopterPilotBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterPilotBehaviour.kt), [HelicopterFlightController.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterFlightController.kt), [BenchAttackOrbit.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/BenchAttackOrbit.kt), [BenchOrbitContact.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/BenchOrbitContact.kt) |
| Helicopter gunners and passengers | [HelicopterGunnerBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterGunnerBehaviour.kt), [HelicopterRideBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterRideBehaviour.kt) |
| Aircraft view range and LOD | [integration/sbw/client/AircraftVisibility.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/client/AircraftVisibility.kt), [AircraftLods.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/client/AircraftLods.kt), [SbwAircraftRendering.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/client/SbwAircraftRendering.kt), mixin `EntityTrackingRangeMixin.java` |
| Drones | [DroneOperatorBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/DroneOperatorBehaviour.kt), [DroneFlightController.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/DroneFlightController.kt), [AntiDroneBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/AntiDroneBehaviour.kt) |
| Mortars | [MortarCrewBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarCrewBehaviour.kt), [MortarClaims.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarClaims.kt), [MortarCycle.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarCycle.kt), [MortarDeployment.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarDeployment.kt) |

### Members, supply and display

| What to change | Where to look |
|---|---|
| A class's weapon, stats and combat ranges | [npc/NpcClass.kt](../common/src/main/kotlin/com/sbwnpc/squad/npc/NpcClass.kt); gear is issued in `NpcEntity.applyRole` and `integration/sbw/SbwGear.kt` |
| An NPC's name, experience and promotion | [experience/ExperienceRules.kt](../common/src/main/kotlin/com/sbwnpc/squad/experience/ExperienceRules.kt), `NpcEntity.gainExperience` / NBT saving; thresholds in [SquadConfig.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/config/SquadConfig.kt) |
| Awards for damage, assists and healing | [ExperienceAwards.kt](../common/src/main/kotlin/com/sbwnpc/squad/experience/ExperienceAwards.kt), [ExperienceEvents.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/experience/ExperienceEvents.kt); mixins `VehicleExperienceMixin` and `ExperienceHealingMixin` |
| The squad card and merging squads | [SquadDetailsScreen.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/screen/SquadDetailsScreen.kt), [SquadDetailsSnapshot.kt](../common/src/main/kotlin/com/sbwnpc/squad/network/SquadDetailsSnapshot.kt), [SquadMerging.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/SquadMerging.kt), `SquadManager.merge` / `reconcileMembership` |
| Ranks and squad presets | [npc/NpcRank.kt](../common/src/main/kotlin/com/sbwnpc/squad/npc/NpcRank.kt), [npc/SquadPreset.kt](../common/src/main/kotlin/com/sbwnpc/squad/npc/SquadPreset.kt) |
| Spawning and choosing a safe spot | [SquadDeployment.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/SquadDeployment.kt), [SafeSpawn.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/SafeSpawn.kt) |
| Barracks production and queue | [BarracksBlockEntity.kt](../common/src/main/kotlin/com/sbwnpc/squad/block/entity/BarracksBlockEntity.kt), [BarracksRecruitmentQueue.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/BarracksRecruitmentQueue.kt), [RecruitmentRoster.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/RecruitmentRoster.kt) |
| Supply and NPCs looking for it | [SupplyBlockEntity.kt](../common/src/main/kotlin/com/sbwnpc/squad/block/entity/SupplyBlockEntity.kt), [SupplyPoints.kt](../common/src/main/kotlin/com/sbwnpc/squad/block/entity/SupplyPoints.kt), [ResupplyBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/ResupplyBehaviour.kt), [OutOfAmmoBehaviour.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/OutOfAmmoBehaviour.kt) |
| Limiting a retreat without ammo when there is no Supply | [AmmoRetreat.kt](../common/src/main/kotlin/com/sbwnpc/squad/entity/ai/AmmoRetreat.kt), called from `OutOfAmmoBehaviour` |
| Faction colours and names | [npc/SquadFaction.kt](../common/src/main/kotlin/com/sbwnpc/squad/npc/SquadFaction.kt) |
| Hostility and alliances | [SquadTeams.kt](../common/src/main/kotlin/com/sbwnpc/squad/team/SquadTeams.kt), [Diplomacy.kt](../common/src/main/kotlin/com/sbwnpc/squad/team/Diplomacy.kt), [AllianceRegistry.kt](../common/src/main/kotlin/com/sbwnpc/squad/team/AllianceRegistry.kt) |
| Highlighting selected NPCs in the world | [SelectionHighlight.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/SelectionHighlight.kt), [SquadSelection.kt](../common/src/main/kotlin/com/sbwnpc/squad/squad/SquadSelection.kt) |
| Map markers, transparency, menus and targets | [SquadMapPlugin.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/journeymap/SquadMapPlugin.kt), [MapShapes.kt](../common/src/main/kotlin/com/sbwnpc/squad/integration/journeymap/MapShapes.kt); the data in `MapFeed.kt` and `client/MapState.kt` |
| The squad menu, recruitment, Supply, diplomacy | [client/screen/](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/screen/); each screen is named after its task |
| Quick commands and keys | [HudClientEvents.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/HudClientEvents.kt), [HudOverlayState.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/HudOverlayState.kt), [HudKeys.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/HudKeys.kt) |
| Textures and translations | [assets/sbwnpc/](../common/src/main/resources/assets/sbwnpc/), [lang/en_us.json](../common/src/main/resources/assets/sbwnpc/lang/en_us.json); [skins](skins/README.md) |

## Tests, build and diagnostics

| Group | Location |
|---|---|
| HTN core tests | [common/src/test/kotlin/com/sbwnpc/squad/ai/](../common/src/test/kotlin/com/sbwnpc/squad/ai/) |
| Tactics execution tests | [neoforge/src/test/kotlin/com/sbwnpc/squad/combat/tactics/](../neoforge/src/test/kotlin/com/sbwnpc/squad/combat/tactics/) |
| What the tactics tests cover, and their limits | [TACTICAL-TESTS.md](TACTICAL-TESTS.md) |
| Server scenes with real NPCs | [neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/); templates in [neoforge/src/gameTest/resources/data/sbwnpc/structure/](../neoforge/src/gameTest/resources/data/sbwnpc/structure/); how to run: [HEADLESS-TESTS.md](HEADLESS-TESTS.md) |
| Tests of the other modules | [neoforge/src/test/kotlin/com/sbwnpc/squad/](../neoforge/src/test/kotlin/com/sbwnpc/squad/), laid out like the main code |
| Build, formatting and test tasks | [build.gradle.kts](../build.gradle.kts) |
| Formatting settings | [.editorconfig](../.editorconfig) |
| JAR files | `build/libs/` |
| Test reports | `<module>/build/reports/tests/test/`, `<module>/build/test-results/test/` |
| Attack scenes and work counting | `neoforge/src/gameTest/.../AttackDeploymentGameTests.kt`, `HtnTacticsGameTests.kt`, `NativeCombatWork.kt` |
| Replaying a saved battle and the causes of idling | `neoforge/src/gameTest/.../SavedBattleGameTests.kt`, `SavedBattleReplay.kt`, `CombatParticipationTrace.kt` |
| Headless GameTest world and reports | `neoforge/build/gametest/server/`, `neoforge/build/reports/gameTest/` |
| BuildFlags generation | `common/build.gradle.kts`; output in `common/build/generated/buildFlags/` |
| Diagnostic log groups | [combat/LogGroup.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/LogGroup.kt) |
| Diagnostic settings | [combat/DebugFlags.kt](../common/src/main/kotlin/com/sbwnpc/squad/combat/DebugFlags.kt); in game, `config/sbwnpc-debug.toml` |
