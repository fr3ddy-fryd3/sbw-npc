# Tactics execution: `combat/tactics`

Tactical decisions are made by the HTN controller: operations, squads, teams and NPCs are described
in [AI-RUNTIME.md](AI-RUNTIME.md) and [AI-ARCHITECTURE-DESIGN.md](AI-ARCHITECTURE-DESIGN.md). The
`combat/tactics` package in `common/` holds the services that carry those decisions out in the
Minecraft world. It decides nothing itself: it does not choose to attack or go round, and keeps no
plan.

The former template controller (FLANK, BOUND, ENCIRCLE and others), with its states, selection rules
and attack operations, has been removed. The scenes that tested it are listed in the issue that
removed it.

## Callers

- `ai/integration/NativeTactics` collects a squad snapshot through `TacticalSnapshotCollector` and
  hands out NPC assignments.
- `ai/integration/NativePositionAction` carries out one assignment. It creates a `TacticalTask`,
  calls `TacticalPositions.find` with its own point policy, then walks the NPC along the path found.
- SmartBrainLib behaviours (`GunAttackBehaviour`, `SquadTargetSensor` and others) read the assignment
  through `SquadTactics` and the squad's shared contacts.

## Files

| File | Responsible for |
|---|---|
| `SquadTacticalState` | The squad's shared contacts and the last snapshot the HTN planned from. No state machine, no plan. |
| `SquadTactics` | Entry point for behaviours: an NPC's assignment, contact reports, permission to fire. |
| `TacticalSnapshotCollector`, `TacticalModel`, `TacticalTerrain` | Immutable snapshot: members, contacts, incoming fire, bounded terrain probes. |
| `TacticalTask` | Execution state of one position search: search cursor, chosen point, path, progress. Owned by the executor. |
| `TacticalPositions`, `TacticalPositionPolicy`, `TacticalPositionScore` | Search for a firing or transit point against real colliders, rays and navigation. The caller's policy adds its own constraints. |
| `TacticalRoutes`, `TacticalRouteValidation`, `TacticalGoalProgress`, `DryGround` | Legs along the walked route, checking a built path, progress to the goal, moving a point off water onto land. |
| `TacticalClearance`, `TacticalContactApproach`, `TacticalAim`, `TacticalEvidence` | Distance to known enemies, approach to a contact's area, aim from a point, evidence of fire. |
| `TacticalFirePosts`, `TacticalFrontPost`, `DefensiveOverwatch` | Local firing-post search, a firing strip, protected high posts on defence. |
| `TacticalTargets`, `TacticalTargetSelection` | Target choice from the shared contacts without reassigning on every scan. |
| `TacticalBudget`, `TacticalRules` | Shared path-search budget; order constraints during the search. |
| `AttackStrength` | An attack's combat strength in the world: measures it when it starts (remnant, refill first, or its base), resumes it after a refill, and picks where a called-off attack falls back to. |

## Rules

- The position search does not branch on the kind of manoeuvre. Special conditions come in the
  `TacticalPositionPolicy` the caller passes.
- `TacticalTask` is execution state, not an assignment. The assignment is the HTN's
  `PositionIntent`; recomputing the same assignment keeps the search and the path
  (`NativePositionAction.revise`).
- Every real action goes through the same physical checks: colliders, rays, path, safe distance
  and firing lane.
