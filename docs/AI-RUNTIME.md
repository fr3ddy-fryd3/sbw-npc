# Tactical core: what is implemented

Code in `develop`: the core is in `common/src/main/kotlin/com/sbwnpc/squad/ai/`, the adapter
`ai/integration` beside it in `common/`. Ground tactics run in game through `NativeTactics`; the former
template controller has been removed. This document describes the contracts that exist now. The
target design they come from is [AI-ARCHITECTURE-DESIGN.md](AI-ARCHITECTURE-DESIGN.md); how they are
tested is in [TACTICAL-TESTS.md](TACTICAL-TESTS.md).

## Boundaries

`ai/planning` does not depend on Minecraft, NPCs, squad SavedData or the former tactical states. The
core works with typed tasks, actions, conditions, observations and resources. The domain's HTN
methods define the ways of carrying out an order. Utility compares the allowed options; execution
and cancellation belong to the runtime.

`ai/coordination` defines the joint operation and the hand-over of subtasks. It does not move NPCs
or touch the world either. Orders come from a principal, and the right to command is checked
separately from being allied. Commander AI, with its resources, army and territorial behaviour, is a
separate future feature.

## Core contracts

| Module | Responsibility |
| --- | --- |
| `FactSnapshot` | One agent's observations, their source, when they were received and how long they hold. Re-confirming an unchanged value does not invalidate a plan that depends on it. |
| `UtilityEvaluation` | Pure scoring of the allowed options with the factors explained; a ban and missing data are kept apart from a weight. |
| `HtnDomain` | Typed compound and primitive tasks, alternative methods, and dependencies for sequential or parallel execution. |
| `HtnPlanningSession` | A search kept between updates, backtracking to an alternative, depth and work limits, dependencies on facts. |
| `PlanRuntime` | Ownership of action instances and resources, checking actual completion, stop and resume, keeping untouched actions under a new plan. |
| `PlanningAgent` | Life cycle of search and execution. A new order withdraws the old work; repair looks for a replacement while the still-valid actions go on. A failed search is not retried every tick with unchanged input. |
| `ActionRegistry` | The checked boundary between a typed action and its concrete executor. |
| `AssignmentLedger` | A single parent per subtask, authority, assignment generation, ordered results and cascading withdrawal. |
| `DelegationAction` | An executable HTN node that hands an intent to a child agent and returns its confirmed result; a stop is passed down. |
| `OperationRegistry` | One operation per shared order, an atomic check of the selection, a separate pending reinforcement and a check of physical entry. |

Effect projection is its own type, and the execution API does not accept it in place of
observations. Independent branches do not use each other's predicted effects; conflicting
predictions are rejected when branches merge.

An execution id holds the owner, the order, the semantic task, the generation and the runtime
session. A late reply to an old executor does not update the new one. A new assignment for one child
does not withdraw a sibling branch. Cancelling an operation goes through the owners of the
assignments down to the NPCs.

Task and action parameters and fact values must be immutable domain values. Typing checks classes
and ownership; it does not make a mutable object passed in immutable.

## In game

The production `SquadTactics` hands ground tactics to `NativeTactics`.

| Layer | In-game implementation |
| --- | --- |
| Order input | `IssuedCommand` keeps immutable UUIDs, the principal, the original order and the kind of goal in NBT. Internal DEFEND/RETREAT do not replace that intent. The HUD, map and tool use the server's order methods; a shared order creates a joint operation. |
| Observations | `TacticalSnapshotCollector` collects real contacts, ammunition, incoming fire, role constraints and the positions of loaded NPCs. An operation's contacts are merged separately from authority; a pending reinforcement does not count as cover. A loaded NPC that is missing does not count as a confirmed loss. |
| Domain | `TacticalDomain` holds separate HTN domains for the operation, squad, team and NPC. The squad's methods are its strategies (advance, hold, gaining height, withdrawal), scored by `SquadManners`. Hard conditions are not replaced by weights. |
| Attack | `AttackPhase` (deploy, assault, clear) picks the operation's HTN method; `AttackProgress` keeps its history and the assault and clearing roles; `OperationTactics` maps a member's situation to his duty. `OperationLosses` turns the operation's losses into a standing (steady, review, withdraw). `ReinforcementMarch` decides when a reinforcement on its way stops to fight; `UnverifiedContacts` picks where scouts look. |
| Geometry | `OperationFront` proposes a curved front from the known enemy positions, with fire support and a rear guard. `TaskTeamAllocator` keeps teams of up to six through changes of strength. A geometric point is not yet a reachable or occupied post. |
| Execution | `NativePositionAction` owns the private search cursor, the concrete route and the post reservation. It uses the existing native A*, collisions, budget queues, firing checks and role constraints. A search can return a budget wait or a local failure; they mean different things. |
| Area completion | A separate operation action requires physical occupation, inspection and 600 safe ticks (`AreaControlClock`). Gaps in observation or loading pause the count; a new contact or incoming fire resets it. SEARCH passes intermediate points and finishes its approach at its target. |

A continuous post assignment keeps the parent's ownership after actual arrival. Losing the post makes
the subtask active again. Revising an intent keeps the suitable child executors; feedback from the
old revision is rejected. An asynchronous executor gets a new generation by default, not a
synchronous revision.

Medical, grenade, vehicle and supply actions keep their own constraints and priorities. Helicopters
and crews use the existing specialised executors.

## Limits

Having a general HTN does not mean the domain already holds every tactic. The strategy options are
the ones listed above. Complex manoeuvres, separate plans against armour and a full assessment of
every kind of terrain are not proven by the existing scenes. The 600-NPC scene measures NPCs actually
moving and the server tick cost; it does not prove a fight of 600 shooters runs at speed, and client
FPS with 150 NPCs in view needs its own client-side measurement.

`HierarchyExecutionTest` links an operation, two squads, four teams and eight NPCs with simulated
movement. It checks that a confirmed result propagates, that one branch can stop, and that a new
order cancels in cascade. It tests the hierarchy contract; it is not a Minecraft scene and does not
prove the quality of formations.
