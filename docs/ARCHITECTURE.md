# Architecture

A map of the codebase for whoever picks it up next. It covers how the parts fit together and where
things go; the code's own KDoc covers the details. Build, workflow and coding rules are in
[CLAUDE.md](../CLAUDE.md).

All Kotlin sources live under `src/main/kotlin/com/sbwnpc/squad/`; paths below are relative to it.

## The big picture

The addon is a NeoForge mod that adds one entity, `NpcEntity`, and everything needed to group,
command and supply it. The pieces, roughly from the outside in:

- **Player input**: the squad tool item, the command screens and the quick-command HUD on the
  client, sent to the server as network payloads.
- **Squads and orders**: `SquadManager` holds every squad (members, order, objective, owner) and
  persists it per world.
- **NPC brains**: each `NpcEntity` runs a SmartBrainLib brain that reads its squad's order and
  decides what to do: march, take cover, shoot, crew a vehicle, heal, resupply.
- **Shared combat and routing services**: perception, fire control, long-route planning and the
  per-tick budgets that keep a hundred NPCs from stalling the server.
- **The outside world**: SuperbWarfare guns, vehicles, mortars, grenades and drones, reached only
  through ports so the rest of the code doesn't depend on SBW directly.

## Package layout

| Package | What lives there |
|---|---|
| (root) | `SquadMod` (mod entry point), `ServerTick` (server-wide per-tick drivers), `ServerLifecycle` (clears runtime state when a world stops) |
| `init/` | Deferred registers: entities, items, blocks, block entities, SmartBrainLib sensors and memory types |
| `entity/` | `NpcEntity` itself, posture, stuck recovery, and live per-level indexes (`NpcRegistry`, `EntityIndex`, drone and grenade registries) |
| `entity/ai/` | Brain behaviours (`*Behaviour.kt`), the target sensor, and the helpers they share: flight controllers, march and route progress trackers, claim registries for mortars and transport seats |
| `squad/` | Squad model and persistence, orders, selection, deployment, routes (patrol routes and recording), radio reports, chunk loading for squads away from players |
| `npc/` | Static game data: classes (`NpcClass`), ranks, factions, squad presets, vehicle model choices |
| `combat/` | Perception (vision, hearing, sightlines, team awareness), fire control (friendly-fire guard, fire allocation, firing spots, cover positions), grenades, withdrawal, suppression, tick budgets, debug switches |
| `route/` | Long-route planner over a cell grid (`CellPlanner`) for walkers and vehicles, the remembered ground map, and the shared planning budget |
| `vehicle/` | Helicopter flight and airspace separation, pilotless helicopters, boats (water map and routes), tree avoidance, driver allegiance |
| `team/` | Factions as vanilla scoreboard teams (`SquadTeams`), alliances (`AllianceRegistry`) and the voting rules that change them (`Diplomacy`) |
| `block/` | Barracks (respawns a squad's missing members) and supply point (refills gear) blocks and their block entities |
| `item/` | The squad tool (recruit and command modes) and how it is granted |
| `network/` | `Payloads.kt` (every `CustomPacketPayload`), `ModNetwork` (registration and server handlers), snapshot builders the screens read |
| `client/` | HUD, key bindings, tool input, client payload handlers, NPC model and renderers, map state, screens under `client/screen/` |
| `map/` | `MapFeed`: what each player's map shows, pushed from the server once a second |
| `domain/port/` | Interfaces for everything taken from SuperbWarfare (`Guns`, `Vehicles`, `Mortars`, `Grenades`, `Drones`, `Gear`, `PlayerSupply`, client-side `GunPoses`, `GunRendering`) and the `Ports` object that holds the implementations |
| `integration/sbw/` | The SuperbWarfare adapter: implements the ports, plus SBW-specific extras (creative tab, aircraft snapshots, boarding); `integration/sbw/client/` holds the rendering side |
| `integration/journeymap/` | Optional JourneyMap plugin that draws the map feed and takes orders from the map |
| `config/` | `SquadConfig` (server config, `serverconfig/sbwnpc-server.toml`) and `DebugConfig` (debug builds only) |
| `util/` | Small shared helpers: timers, terrain queries, item stack data, the perf probe |

Java mixins live in `src/main/java/com/sbwnpc/squad/mixin/`, listed in
`src/main/resources/mixins.sbwnpc.json` (common and client sets). Most patch SuperbWarfare classes
(vehicle boarding, shooting and gun data, aircraft rendering, auto-aim); the rest patch vanilla
(entity alliance checks, tracking range, key mappings).

## Startup and wiring

`SquadMod`'s constructor does all the wiring, once:

1. Points each port in `Ports` at its SBW implementation (`Ports.guns = SbwGuns`, ...). The two
   client-only ports are set in `FMLClientSetupEvent` handlers inside `integration/sbw/client/`.
2. Registers the server config, and the debug config when the jar was built with
   `./gradlew buildDevelop`.
3. Registers the deferred registers from `init/` and forces `ModSensors`/`ModMemories` to load so
   SmartBrainLib's registrations happen before the registries close.

Everything else hooks in through `@EventBusSubscriber` objects: the game events each subsystem
needs are subscribed to in the file that handles them, not centrally.

## The SuperbWarfare boundary

SuperbWarfare is a hard dependency today, but the code is arranged so it could be swapped out:

- Only `integration/sbw/` (and the mixins) may import `com.atsuishio.*`. Everything else talks to
  `Ports.guns`, `Ports.vehicles` and so on. `SbwBoundaryTest` fails the build if anything outside
  the adapter imports SBW.
- Port interfaces describe what the addon needs in its own words (a `HandGun` with rounds per
  minute, muzzle velocity, `operate()`), not SBW's API shape.
- To use something new from SBW: add it to the matching port in `domain/port/`, implement it in
  `integration/sbw/`, and call it through `Ports`.

The build pulls SBW from a local checkout via `includeBuild("SuperbWarfare")` because the adapter
uses SBW internals that aren't in its published jar.

## NPC brains

`NpcEntity` is a SmartBrainLib `SmartBrainOwner`. Its brain is declared in `NpcEntity.kt`:

- **Sensor**: `SquadTargetSensor` picks every NPC's target in one priority chain (vehicles
  attacking it, the squad's focus, whoever hurt it, what it and its side can see) and sets both
  the `ATTACK_TARGET` memory and `mob.target`.
- **Core tasks** always run, whatever else is going on: doors, seeking cover, mortar operator and
  loader, drone operator and anti-drone, vehicle crew, helicopter pilot, gunner and passenger,
  vehicle combat support, medic, self-treatment, and grenade evasion (last, so it can override the
  navigation set by the others).
- **Idle tasks** run when there is no attack target: resupply, investigate a noise or alert,
  vehicle transport for long trips, and `SquadOrderBehaviour`, which carries out the squad's order
  (Move, Attack, Defend, Patrol, Barrage, Retreat).
- **Fight tasks** run while there is an attack target: `GunAttackBehaviour` (positioning and
  shooting), out-of-ammo retreat, melee, and grenades.

Behaviours share state through memory types registered in `init/ModMemories.kt` (for example
`COVER_HOLD`, which tells the shooting and grenade behaviours that cover-seeking owns the NPC right
now). Where two NPCs could grab the same thing, a claim registry decides (`MortarClaims`,
`VehicleTransportClaims`, `DroneLinks`).

Idle behaviours have no automatic exclusivity, so each one's start conditions are written to
exclude the others' cases. When adding a behaviour, check its neighbours' conditions and the
comments above the task lists in `NpcEntity`.

## Squads and orders

- `Squad` is one squad's data: members (entity UUIDs), faction, owner, order, objective, focus
  entity, barracks, rank, patrol route, Move rally state. `orderStamp` changes on every new order
  so members can tell a fresh command from the one they are already carrying out.
- `SquadManager` is the `SavedData` that holds all squads, server-wide. Get it with
  `SquadManager.get(level)` or `get(server)`.
- `SquadOrder` lists the orders and `SquadOrder.availableFor(...)` is the single source of which
  orders a squad of a given shape (tank, mortar, gunship, transport) can be given; screens, the HUD,
  map menus and server validation all use it.
- `SquadSelection` is the per-player, server-side selection made with the squad tool. Its accessors
  return live collections; copy before keeping a snapshot.
- `OrderArrival` turns Attack and Retreat into Defend once most of the squad has arrived.
- `SquadChunkLoader` keeps chunks loaded around busy squads so they keep moving and fighting away
  from players (bounded by `SquadConfig.ACTIVE_SQUAD_CHUNK_LIMIT`).
- `SquadReports` sends radio calls (contact, losses, low ammo, arrival) to the owner's chat.
- `RouteManager` (`SavedData`) holds named patrol routes per player; `RouteRecording` records new
  ones as the player walks.

## Factions and diplomacy

- Each faction is a vanilla scoreboard team `sbwnpc_<faction>` (`SquadTeams`). NPCs join their
  faction's team; players never do. A player's chosen faction is kept in `PlayerFactionRegistry`
  (`SavedData`).
- Hostility is `SquadTeams.isHostile(...)`, which asks `Diplomacy` whether the two factions are
  friendly. `AllianceRegistry`
  (`SavedData`) stores who is allied with whom as bit masks; `Diplomacy` holds the voting rules and
  ticks pending votes.

## Combat services

`combat/` is shared logic that behaviours call rather than behaviours themselves:

- **Perception**: `Vision`, `DetectionSightline`, `Sightline`, `Hearing` and `FootstepHearing`,
  `TeamAwareness` (what each faction currently knows about, shared across its NPCs), `Alarm`.
- **Fire control**: `FriendlyFireGuard` (models the firing cone, not a zero-width ray),
  `FireAllocation` (spreads fire across several enemies), `FiringSpots`, `CombatPosition`,
  `VehicleTargeting`, `TankWeaponSelection`, `GrenadeThrower` and `GrenadeHazard`.
- **Squad tactics**: `SquadFormation`, `Withdrawal` (bounding overwatch on Retreat), suppression.
- **Budgets**: `TickBudget` caps the expensive terrain raycasts per server tick, because a whole
  squad tends to search for cover or firing spots on the same tick.

## Routing and vehicles

- Short moves use vanilla pathfinding through `VehicleAwareNavigation` and `NpcMoveControl`.
- Long trips use `route/CellPlanner`, a best-first search over coarse cells. `Walking` and
  `Driving` describe what a man on foot or a given vehicle can cross; `Ground` is the terrain they
  read, backed in game by `GroundMap`, which remembers terrain after chunks unload.
- `PlanBudget` runs all long-route searches from the server tick with a shared work budget, so a
  big order doesn't freeze the server.
- `SquadMarch` plans one route for a whole squad on a long march; boats use `vehicle/WaterRoutes`
  and `WaterMap` the same way.
- Helicopters have their own flight controllers in `entity/ai/` and `vehicle/Airspace` keeps them
  apart.

## Networking

- Every payload is declared in `network/Payloads.kt` and registered in `ModNetwork` on
  `RegisterPayloadHandlersEvent`. Server-side handlers are in `ModNetwork`; client-side handlers
  are in `client/ClientPayloadHandlers`.
- Server handlers validate what the client claims (reach to a block, order allowed for the squad,
  bounded route length) rather than trusting it.
- Screens get their data as snapshots built on the server (`SquadSnapshot.kt`, `RouteSnapshot.kt`).
- `StreamCodec.composite` takes at most 6 fields; bigger payloads hand-write the codec.

## Client

- `client/` has the HUD (`HudClientEvents`, `HudKeys`, `HudOverlayState`), tool input
  (`ToolInputEvents`, which catches Ctrl-clicks client-side), payload handlers, the NPC model and
  renderers, and all screens under `client/screen/`.
- SBW-specific rendering (gun poses in NPC hands, static gun meshes, aircraft level of detail and
  visibility) is under `integration/sbw/client/` behind the `GunPoses` and `GunRendering` ports.
- `MapState` holds the latest map feed for the JourneyMap plugin.

## Persistence and runtime state

Saved per world (`SavedData`):

| Class | Holds |
|---|---|
| `squad/SquadManager` | All squads, and deletions of members that were unloaded at the time |
| `squad/PlayerFactionRegistry` | Each player's chosen faction |
| `squad/RouteManager` | Named patrol routes |
| `squad/ReportMutes` | Which players muted radio reports |
| `team/AllianceRegistry` | Alliances between factions |
| `block/entity/SupplyPoints` | Supply positions and factions, per dimension |
| `block/SupplySpawns` | Players who respawn at a supply point, and which one |

Everything else is runtime only: indexes, claims, caches, selection, awareness. Most of it sits in
Kotlin `object`s, so it outlives a world in single-player. `ServerLifecycle.onServerStopped` clears
it all; **any new static runtime state must be cleared there too**, or UUIDs leak into the next
world.

Server-wide periodic work runs from `ServerTick` (selection particles, pilotless helicopters,
diplomacy, map feed, chunk loading, order arrival, reports, route planning, stray rounds). Per-NPC
work runs in the NPC's own brain.

## Debugging

- `./gradlew buildDevelop` bakes `BuildFlags.DEBUG_ENABLED = true` into the jar (generated by the
  `generateBuildFlags` Gradle task). That turns on `DebugFlags` (particle markers for cover and
  firing-spot choices) and registers `DebugConfig`.
- Trace logging is grouped by `LogGroup`; enable a group by naming its key in
  `config/sbwnpc-debug.toml`. Lines print as `[key-debug] ...`.
- `util/PerfProbe` answers whether a frame-rate drop is on the server thread or the render thread.

## Tests

JUnit 5 tests in `src/test/kotlin/com/sbwnpc/squad/`, mirroring the main packages. They cover the
pure logic that can run without a game: sightlines and vision, formations, fire decisions, flight
controllers, route progress, the cell planner, static gun models. `SbwBoundaryTest` scans the sources
for SBW imports outside the adapter, and `SbwMixinIntegrationTest` checks that the mixins still
apply to the real SBW classes. `SavedWorldRoutesTest` replays routes on a
terrain export from a real world named by the `SBWNPC_GROUND` environment variable and is skipped
without one.

Gameplay behaviour is verified by playtesting in game, not by these tests.

## Where to add things

| To add | Do this |
|---|---|
| An NPC behaviour | A new `ExtendedBehaviour` in `entity/ai/`, added to the right task list in `NpcEntity`. New shared state goes in `ModMemories`. |
| A network message | A payload in `Payloads.kt`, registered and handled in `ModNetwork` (server) or `ClientPayloadHandlers` (client). Validate on the server. |
| Something from SuperbWarfare | A method on the port in `domain/port/`, implemented in `integration/sbw/`. Never import SBW elsewhere. |
| Persistent world state | A `SavedData`, or a field on an existing one with save and load. |
| Runtime-only global state | An `object` with a `clearAll()`, called from `ServerLifecycle.onServerStopped`. |
| Periodic server work | A `tick(server)` called from `ServerTick`. Spread expensive work with a budget like `TickBudget` or `PlanBudget`. |
| A server setting | An entry in `SquadConfig`. |
| An NPC class or faction | `npc/NpcClass` or `npc/SquadFaction`, plus textures and lang entries under `src/main/resources/assets/sbwnpc/`. |
