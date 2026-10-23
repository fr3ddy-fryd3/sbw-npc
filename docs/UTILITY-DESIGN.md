# Utility in the AI architecture

Status:

- **Implemented:** the general utility evaluation (`UtilityEvaluation` in
  `common/src/main/kotlin/com/sbwnpc/squad/ai/planning/Utility.kt`) and utility-scored HTN
  methods, used at the squad and team levels: one squad method per strategy (advance, hold, gain
  height, withdraw, bound by halves, regroup) scored and kept stable by `SquadManners`, the squad's
  approach (flank or frontal, `SquadApproaches`) and a team's rush (together or in pairs,
  `TeamRushes`). NPCs choose by preconditions and method order, not by utility.
- **Proposed, not implemented:** utility at the operation and Commander levels, the scoped contexts
  (`CommanderContext`, `OperationContext`, `SquadContext`) and the examples below. They are design
  for later work, not a description of the code.

## Purpose

Utility scores how preferable options are, given the information available and set preferences. A
score is not a probability of success and not permission to act. Each level has its own options and
data; their scores are not compared across levels.

The HTN can already pick alternative methods by their conditions. Utility adds a comparison of
preferences where several methods or goals are allowed, with different cost, risk and expected
benefit. So no action has to go through utility just because it exists in the architecture.

Proposed uses:

| Decision owner | Options | Factors available |
|---|---|---|
| Commander | Defend an area, take an objective, produce units, keep a reserve | Known threats, territory, free resources, reserve, character, the army it has |
| Operation | Frontal pressure, a left or right flank, wait for reinforcements, reassign roles | The goal and its limits, squad reports, approaches, real support available, how fresh the information is |
| Squad | Carry out its role by an available approach, change the method, regroup, or report it cannot | Its task, the local situation, members, ammunition, health, terrain, allowed commitments |

An NPC's positions and paths are chosen by the local execution service. Numerical scores of
candidates can help it, but that is a separate problem with its own context; it does not read the
Commander's budget or the operation's strategy scores.

## Example (proposed)

Two squads got a shared ATTACK. The operation weighs its options. The numbers only illustrate the
interface; they are not a balanced formula:

| Option | Allowed | Score | Why |
|---|---|---|---|
| Frontal pressure | Yes | 0.46 | Quick approach, high expected exposure |
| Left flank | No | Not scored | Leaves the area the order allows |
| Right flank | Yes | 0.78 | Cover and an approach available, acceptable cost in time |
| Wait for reinforcements | Yes | 0.62 | Likely reinforcement from a confirmed report, cost of waiting |

Utility returns ranked proposals. The decision owner checks the conditions for changing its current
decision and asks for a plan for the chosen option. The HTN breaks it down: secure support, send a
suitable squad round the flank, give its teams their subtasks, then carry out the agreed actions.
Whether support is actually ready is confirmed by execution, not by a positive factor in a score.

If one approach turns out impossible, the owner gets a result with the area that was checked and the
reason. That is new information for a review. The evaluator itself does not change the order, remove
tasks or widen the search.

## The scoring contract

Input: an immutable snapshot for the level, the allowed options, a preference profile, relevant
dependencies and the data version.

Output: the option's id, its score, the factors and their contributions, unknown data and the basis
of the score. Excluded options are kept separately with the reasons they are not allowed.

Being forbidden is different from being unattractive. Breaking authority, the order, a resource or a
physical limit excludes an option; a high aggression weight does not make up for a ban. Missing
information gets an explicit `Unknown` state, not "no path" or "no enemies". Where needed it creates
a scouting or clarification task, not invented certainty.

Factors use one agreed scale within a decision and documented curves. Adding up convenient numbers is
not enough: correlated factors can count one risk twice, and an average can hide a critical
shortfall. The aggregation and the behaviour at the boundaries are checked for each set of options.

## Component boundaries

1. Perception, the resource model and tactical queries supply dated facts. The evaluator does not
   run Minecraft queries, rays or A* and does not change the facts.
2. Domain modules describe the options and their own factors. The general utility mechanism knows
   nothing of specific manoeuvres, weapon types or a particular NPC's state.
3. The decision owner chooses when to review and applies the stability policy. The evaluator owns no
   current plan, interrupt timers or execution resources.
4. The HTN builds the plan from goals, conditions and allowed methods. Actual execution results
   refine whether it is feasible.
5. The runtime carries out the plan, holds resources and returns results. It has no direct access to
   the Commander's preference formulas.

The key contract: `snapshot + options + preferences → scores and explanations`. No side effects are
allowed in this mapping.

Snapshots are scoped to their level. A factor gets what it needs through its level's contract. A
shared mutable blackboard from which any factor can read or rewrite any plan is not allowed. Sharing
algorithms to save work does not mean one shared instance of state for everyone.

## Stable choices

A small change in a score should not keep switching the flank, the goal and the assigned members. A
separate decision policy is used: a clear advantage for the new option, a cost of switching,
conditions to carry on, and a suitable point to hand over resources. For the squad's strategy,
`SquadManners` gives the strategy the squad holds a bonus in its score and, for a short time after
it was chosen, rules the others out (the `squad.manner_lock` fact, whose expiry replans).

If the current option has become forbidden or infeasible, or there is immediate danger, ordinary
stability does not block the needed reaction. Emergency interruption belongs to the safety rules and
the runtime. A higher score alone does not cancel the current task.

Scoring runs in its level's budget, on events and at an allowed rate. Spreading requests over ticks
and delays belong to the work scheduler, not to hidden timers in scoring functions.

## Checks against new coupling

- A new factor changes only the declared comparison of its level's options and does not change
  facts or execution.
- Adding a strategy describes its options, conditions and HTN methods in the domain module; the
  general scoring, cancellation, navigation and firing need no exceptions by strategy name.
- Changing the Commander's character affects its preferences and the risk policy it passes on
  explicitly. It does not rewrite a single NPC's assignments or lift safety limits.
- The same snapshot and profile give reproducible scores, reasons for exclusion included; any
  dependence on time or randomness is explicit.
- Factor interactions are tested in combinations and at boundaries: high risk, an unknown approach,
  a resource just above or below the cost, required support missing.
- Swings of neighbouring scores do not cause endless switching; losing real applicability gets a
  timely reaction.
- The highest-scoring option is not taken as done without actual results from the levels below.

The architecture still has explicit dependencies: strategy uses information, the plan uses
strategy, execution returns results. The task is to keep those dependencies directed, bounded and
testable. Calling something utility does not guarantee that separation.

## Source

[David Graham, An Introduction to Utility Theory](https://www.gameaipro.com/GameAIPro/GameAIPro_Chapter09_An_Introduction_to_Utility_Theory.pdf)
describes comparing option scores, the effect of preferences and the problem of oscillating
choices. The interfaces, context scopes and boundaries proposed here are design decisions for
sbw-npc.
