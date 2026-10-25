# Hostile mob integration boundaries

Issue: [#135](https://github.com/fr3ddy-fryd3/sbw-npc/issues/135). This is an implementation audit,
not a claim that mob combat is implemented. Gameplay remains unchanged while the owner decides
between an explicitly limited first stage and native adapters for the complete requirement.

## Required behaviour

Mobs should consider squad NPCs under their existing player-aggro conditions, including range,
sight and conditional aggression. NPC targeting, contacts and combat should recognise those
enemies. Unprovoked neutral mobs, the owner's pets and mod-declared allies stay neutral/friendly.
The owner also requested no per-mob hardcoded list and no replacement AI system.

## Inputs checked

The audit uses Minecraft 1.21.1 with pinned NeoForge 21.1.228, at develop `ab4a1f86`. The mapped
sources are produced by `:neoforge:createMinecraftArtifacts` in
`neoforge/build/moddev/artifacts/neoforge-21.1.228-sources.jar`. The class paths below are entries
inside that archive; they describe the actual build input rather than a newer API version.

| Native path | Concrete constraint | Consequence |
|---|---|---|
| `ai/goal/target/NearestAttackableTargetGoal.java` | `findTarget` special-cases `Player` and `ServerPlayer` to `level.getNearestPlayer`; other classes use `getNearestEntity`. | An NPC can satisfy ordinary combat conditions and still be absent from the player-only query. |
| `monster/Spider.java`, `SpiderTargetGoal.canUse` | The original goal checks brightness before calling the base goal. | Installing a fresh base target goal discards the day/night condition. Extending the original discovery path can retain it. |
| `monster/EnderMan.java`, `EndermanLookForPlayerGoal` | The goal owns a `Player pendingTarget`, a player-specific anger predicate and casts its target to `Player` during tracking. | Putting an NPC into every player goal is not type-safe. The existing anger predicate itself rejects this substitution with `ClassCastException`. |
| `monster/Guardian.java`, `GuardianAttackSelector` | Its target class is `LivingEntity`, with a selector allowing players, squid and axolotls. | Extending only goals declared with `Player.class` misses a hostile that does attack players. |
| `ai/sensing/PlayerSensor.java` | It builds `List<Player>` from `level.players()` and fills player-typed nearest-player memories. | Adding NPCs to ordinary living-entity sensing does not populate these memories safely. |
| `monster/hoglin/HoglinAi.java` | `findNearestValidAttackTarget` reads `NEAREST_VISIBLE_ATTACKABLE_PLAYER`, after pacification/breeding checks. | Hoglin initiation does not pass through the basic target-goal path. A generic extra goal would bypass its native Brain conditions. |
| `monster/piglin/PiglinAi.java` | Target choice uses player memories, anger, repellent/fear and other Brain state. | It needs native handling rather than a replacement generic pursuit goal. |
| `monster/piglin/Piglin.java`, `AbstractPiglin.java` | Piglins inherit the `Enemy` marker through `Monster`, but do not implement `NeutralMob`. | `Enemy && !NeutralMob` still makes an unprovoked piglin an NPC target, violating the requirement. |
| `net/neoforged/neoforge/event/entity/living/LivingChangeTargetEvent.java` | Both `Mob.setTarget` and `StartAttacking` publish a cancellable replacement event after a candidate exists. | This is useful for respecting cancellation or replacing an existing target, but does not discover NPCs when the native finder returns empty. |

Minecraft class entries above start with `net/minecraft/world/entity/` unless a full path is given.
Player-specific subclasses or selectors in other mods have the same type-safety boundary; an
arbitrary goal subclass can cast the chosen target after discovery, not only inside its predicate.
Catching a cast failure inside a search would not make those later tracking calls safe.

## Existing addon integration points

| Addon code | Required consideration |
|---|---|
| `SquadTeams.isHostile` | Currently requires both sides to resolve to factions. A mob extension needs an explicit aggression policy, while preserving the existing faction result and checking native alliances. |
| `NpcEntity.isEnemy` | Currently admits only NPCs and players before consulting `SquadTeams`; changing the central faction helper alone is insufficient. |
| `SquadTargetSensor.nearestGroundTarget` | Already performs a bounded living-entity query and applies central hostility before sight/notice checks. It need not gain a parallel targeting loop. |
| `Hostiles.within` | Currently enumerates only indexed NPCs and players. Cover, mortar and drone consumers would otherwise disagree with the ordinary target sensor about mob threats. |
| `SquadTactics`, `TacticalSnapshotCollector`, `TeamAwareness` | They already consume living targets and central hostility checks; new enemies should use those same contacts and planning state. |
| `EntityAllianceMixin` / `SquadTeams.sameSide` | The mixin only adds faction alliances to native `isAlliedTo`; it does not replace another mod's positive alliance decision. Calling native alliances from a new hostility guard must not recurse through `sameSide`. |

There is no universal data field by which every mod declares friendliness. Vanilla scoreboard
alliances, `isAlliedTo`, tame-owner relationships and target-event cancellation are concrete
mechanisms to respect; arbitrary private state in another mod requires its own compatibility
contract. A broad marker is not a substitute for those mechanisms.

## Reviewable implementation choices

1. **Explicit first stage:** extend supported goal-based discovery while retaining each original
   goal's conditions; keep natural retaliation for neutral mobs; route accepted targets through
   the existing NPC hostility, sensor and contact model. State unsupported player-specific goal
   subclasses and Brain selectors explicitly. This is useful progress but does **not** satisfy
   “every mob that goes for players”.
2. **Full native coverage:** allow adapters for native player-specific goals and Brain selectors,
   with their own range, pacification, anger, equipment and continuation tests. This retains the
   actual AI, but requires relaxing the ban on per-type integration cases and defining how
   unsupported modded target finders opt in.

A universal extra target goal, unconditional `Enemy` hostility, raw NPC values in player memories
or fake-player stand-ins would either change the requested conditions or introduce a replacement
system. None is implemented here. The owner decision is pending; no gameplay patch is presented
as complete.

## Executable checks

`HostileMobApiGameTests` pins the relevant native contracts in the real server runtime: ordinary
combat conditions versus the player-only query, the actual enderman player predicate, spider
brightness gating, and unprovoked/tamed/mod-allied neutrality. All four passed in report
`mob-api-contracts`: the NPC satisfies native combat conditions at 4 blocks but the query selects
the actual player at 8 blocks; the original enderman predicate throws `ClassCastException`; the
spider rejects daylight and acquires at night; neutral, owner-pet and mod-alliance checks pass.

```bash
./gradlew runGameTestServer \
  -PgameTestFilter=ordinaryCombatConditionsDoNotWidenPlayerQueries,endermanPlayerPredicateIsNotNpcSafe,originalSpiderGoalRetainsItsBrightnessCondition,neutralMarkersDoNotOverrideTameOrModAlliances \
  -PgameTestReportName=mob-api-contracts
```

These are API/compatibility probes, not new mob-combat feature tests or a performance claim.
In-game combat, all mob families and large mob populations remain unverified.
