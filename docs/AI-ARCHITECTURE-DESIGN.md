# AI architecture design: orders, operations, squads and execution

**This is the target design.** It describes where the AI is meant to go, not only what the code does
today. What exists now is in [AI-RUNTIME.md](AI-RUNTIME.md); keep descriptions of the current code
there, and keep this document as the target. The former template controller has been removed. The
numbers and tactical methods below are proposals to be checked, unless the status says otherwise.

The scope is the tactical system: operations, squads, teams and NPCs. Commander AI is a separate
future feature; its requirements below only fix compatibility of the order input. The Commander
entity, economy, army spawning and territorial policy are not implemented here.

## Status

| Section | Status |
|---|---|
| [Five levels of decisions](#five-levels-of-decisions) | **Implemented** for the operation, squad, team and NPC levels (`NativeTactics`, `TacticalDomain`). The Commander level is not (#12). |
| [Separate concepts within a level](#separate-concepts-within-a-level) | **Implemented**: orders (`IssuedCommand`), facts (`FactSnapshot`), strategy (`SquadStrategy`), plan (HTN) and execution (`PlanRuntime`) are separate types. |
| [Orders and authority](#orders-and-authority) | **Implemented** for players: principal, order id, capability checks, idempotent delivery. Commander authority is not. |
| [Information and joint tactics](#information-and-joint-tactics) | **Partly**: contacts carry source and time and are shared per operation; there are no explicit accept/decline handshakes or cover confirmation between squads yet. |
| [Task and executor contract](#task-and-executor-contract) | **Implemented** as `PositionIntent` (assignment) vs `TacticalTask`/`NativePositionAction` (execution), with `ExecutionStatus` results. The names `TaskSpec`/`TaskExecution` were placeholders. |
| [Updates, saving and diagnostics](#updates-saving-and-diagnostics) | **Partly**: dated snapshots and per-decision logs exist; a full per-NPC explanation chain and saving plan state across loads do not. |
| [Creating an operation and joining late](#creating-an-operation-and-joining-late) | **Implemented** (`OperationRegistry`: one operation per shared order, atomic selection check, pending reinforcement, physical entry). The "same point" tolerance is still open. |
| [Commander AI](#commander-ai-army-resources-and-territory) | **Not implemented** (#12). |
| [Teams for subtasks](#teams-for-subtasks) | **Implemented** (`TaskTeamAllocator`, up to six by default, keeping teams through changes of strength). A team chooses by utility whether its movers rush together or in pairs (`TeamRushes`). |
| [Completing an area ATTACK](#proposal-completing-an-area-attack) | **Implemented** (`AreaControlClock`: occupation, inspection and 600 safe ticks, i.e. 30 s), plus attack phases deploy/assault/clear (`AttackPhase`). |
| [Risk and withdrawal](#proposal-risk-and-withdrawal) | **Implemented with other thresholds**: the operation reviews its plan at 30% of its strength lost and withdraws at 60%, counting the dry with the dead and measured from the strength the attack started with; a remnant sent in fights on (`CombatStrength`, #238); a squad's own strategy withdraws at 40% under fire without a way to carry on (`SquadStrategy`). Squads with a barracks fighting on instead of withdrawing: not implemented yet (#57). |
| [Choosing strategies and plans](#proposal-choosing-strategies-and-plans) | **Partly**: the HTN core and utility scoring exist; utility is used for the squad's strategy only, not for the operation or the Commander ([UTILITY-DESIGN.md](UTILITY-DESIGN.md)). |
| [Performance](#performance-150-visible-600-active) | **Partly measured**: a 600-NPC movement scene measures server cost; client FPS with 150 in view is not measured (#11). |

## Agreed requirements

- Hierarchy: player / Commander AI → group of squads → squad → team of NPCs → single NPC.
- Commander AI uses the same order interface as the player but is an entity of its own, with its
  own faction, character, army and resources. It does not hand its squads to the player. The levels
  below choose how to carry out an order.
- A shared order to several selected squads creates a joint operation. A selection of non-allied
  factions is refused. A squad ordered later to the same point joins on arrival.
- Teams of NPCs are formed for a subtask; significant losses and reinforcement can trigger partial
  reassignment.
- Performance target: at least 150 NPCs in direct view on the owner's laptop, and 600 actually
  active NPCs in the world. Whether this is reachable still has to be measured.
- A group of squads has the state and strategy of its joint operation; a squad has its own state and
  strategy. A team carries out a subtask. An NPC chooses positions, cover, path and local actions.
- Order, situation, strategy, current plan and actual execution are different things.
- Passing information between allies and controlling actions jointly have separate contracts.
- Existing Minecraft/SBW navigation, weapons, vehicles and physical checks are kept. Supply from
  vehicles is a separate feature; the architecture leaves room for its tasks and resource limits.

## What studying the old system showed

Historical background: the problems of the former controller that this design answers.

The project already had snapshot/policy/state/task-board separation, separate manoeuvre instances
and cancellation rules, so "add State and Strategy" alone fixed nothing. The dependencies to change
were:

1. `TacticalTask` held the assignment together with the mutable position search, cursors, path and
   local recovery. Changing the assignment could destroy the executor's work.
2. `TacticalPositions` knew the modes and kinds of tactical policies, so a new strategy leaked into
   the search executor.
3. Geometry took part in assigning places, judging arrival, changing waves and recovery at once; a
   change of formation touched several decisions.
4. The outer operation and the squad's local plan both drove movement and task changes, and not
   every reason for a review had its own scope.
5. The source of an order was partly told apart through `issueOrder`/`setOrder`, with no shared
   authority contract for the player and a future Commander AI.

These explained part of the complexity. They did not prove that every in-game failure was
architectural: an isolated search showed a physically closed local sector even without other NPCs.

## Research and the limits of borrowing

[Killzone 3, the developers' chapter](https://www.gameaipro.com/GameAIPro/GameAIPro_Chapter29_Hierarchical_AI_for_Multiplayer_Bots_in_Killzone_3.pdf):
separate levels of individual, squad and commander AI; tasks go down, messages and results are used
above. An individual plan can be kept while its continuation conditions hold. We take the split of
responsibility and the feedback as guides. Our extra levels (group of squads, teams) are this
project's requirements.

[Days Gone, Tobias Karlsson's chapter](https://www.gameaipro.com/GameAIProOnlineEdition2021/GameAIProOnlineEdition2021_Chapter12_Squad_Coordination_in_Days_Gone.pdf):
the squad assigns roles and shared areas, individual AI carries out the role and reacts to
higher-priority circumstances. A useful example of local autonomy. Its automatic merging and
splitting of squads is not carried over to player-made squads without a separate decision.

[The official description of Halo 2's AI](https://learn.microsoft.com/en-us/halo-master-chief-collection/h2/ai/aiengineeringoutline):
a squad hierarchy, areas of allowed action, order end conditions and protection of some orders from
being overridden. Used as an example of explicit authority boundaries, not as a ready
implementation.

These describe specific game systems. They do not prove HTN or any other planner suits this project.
Minecraft has mutable terrain and unloads chunks and entities; a prepared, immutable cover graph
cannot be assumed. The papers are read as architectural examples, not instructions to copy.

## Five levels of decisions

| Level | Owns | Decides | Gets from below |
|---|---|---|---|
| Player / Commander AI | The source and scope of authority for orders | Which goals its groups and squads must achieve | Acceptance, progress, completion, refusal and reasons |
| Group of squads | Members, the joint operation's state and strategy, commitments between squads | Roles, directions, support, reserve and the joint stages needed | Squads' readiness and capabilities, roles confirmed or impossible, actual cover |
| Squad | Carrying out its order or role, the local situation, its strategy and plan | How to carry out the role on the terrain available, and how to split subtasks | Teams' progress, losses, blockages, local capabilities |
| Team of NPCs | Members for the current subtask, its stage and results | Order of actions, local split of functions, conditions for acting together | NPC results, availability of members, execution and interruptions |
| NPC | Its own execution and local resources | The concrete path, position, cover, target, reaction to immediate danger | Physical results of navigation, weapons, vehicles and interactions |

Five levels of responsibility do not need five equally complex planners. All levels share task and
execution contracts; their domains differ in complexity. A team uses the execution and coordination
methods it needs. Levels work at different scales and rates; an NPC's immediate safety is checked at
execution regardless of how often the plan above is reviewed.

In the target system a group of squads is the members of a particular joint operation; no permanent
player group is needed. A team of NPCs is a temporary set for a subtask, not a new saved `Squad`.
Real squads' members and owners are kept.

## Separate concepts within a level

| Concept | Example | Rule |
|---|---|---|
| Order / assigned goal | Attack an area, hold a position, deliver troops | Has an id, a source, limits and end conditions |
| Situation | A contact in view, a report received, incoming fire, enemy above, some members wounded | A set of facts and assessments that hold at once; not one huge table of mutually exclusive states |
| Strategy | Joint flanking, attacking a height, holding, organised withdrawal | Proposes a way to reach the goal from the information and capabilities available |
| Plan | One squad pins, the other opens a flank; inside a squad one team gives fire support | Concrete actions, dependencies and continuation conditions of the chosen decision |
| Execution | An NPC looks for a way, walks, fires, heals or waits for a confirmed condition | Has its own progress, resources, results and reasons for stopping |

For example, a squad can be on ATTACK, in contact, with suppressed men, below the enemy and part of a
flanking operation all at once. These facts need no `ATTACK_CONTACT_SUPPRESSED_BELOW_FLANK` state and
no separate implementation for each combination.

A strategy gets an immutable snapshot and proposes a plan. It does not call navigation or change
another level's tasks directly. Geometry returns recommended areas, directions and limits; arrival
and cover are confirmed by the executors' own reports.

## Orders and authority

A shared input: an order with an `orderId`, an addressee, a source, a goal, execution rules and end
conditions. The player and Commander AI go through the same validation of the addressee's
capabilities. A group order is unfolded into squad assignments that keep the link to the original
order.

A new explicit order, even with the same coordinates, can be a new decision of the player. A
repeated delivery of the same id must be idempotent; this matters for network messages and for a
repeated attack after an ammunition refusal.

A lower level may pause a dangerous action, choose an allowed way to carry out the order or report
that it cannot. Changing the parent's goal needs separate authority. Losing contact does not mean the
enemy died; an unreachable path does not mean arrival.

The automatic withdrawal for ammunition and the end of a helicopter attack are described by an
explicit execution result and an allowed transition. The original order's history is kept; the
current order and the visible behaviour can change under existing game rules. A failed ATTACK must
not resume by accident after resupply.

The player's and Commander AI's armies have different owners. The source of an order needs authority
over the addressed squads: belonging to the same faction or alliance does not grant it. A joint
operation does not hand over ownership of an army. Commander AI decides on its own orders to its
squads; execution results feed its next decisions.

## Information and joint tactics

A contact report holds its source, the time of observation, the last known position, how it was
obtained and how long it is good for. Receiving a report does not reveal the enemy's hidden current
position and does not confirm a shot is possible. Sound, direct sight and an ally's message need
different levels of trust and reaction.

A joint operation uses separate assignments and agreements: who supports which sector, who moves,
who keeps the reserve. A squad accepts or declines an assignment, with a reason. Allied contacts alone
do not create an obligation to attack together.

A squad has one owner of its main tactical assignment at a time. It can exchange information with
several allies and give extra support if that fits its main task. A new order, a member disappearing
or a broken link explicitly release the related commitments.

Cover is confirmed by fresh actual fire events in the right sector. A role's name, an occupied spot
and a turned weapon are not enough. An agreement sets its confirmation criterion, how long it holds
and what happens when confirmation is lost. One blocked flank delays only the actions that really
depend on it.

## Task and executor contract

The assignment (`TaskSpec`) and the execution state (`TaskExecution`) are separate. The assignment
holds the goal, the allowed area, limits, completion criteria and interruption rules. The execution
holds the search cursor, the path, progress, the chosen position and reasons for failures. Ids link
a result to its assignment and owner. (Implemented as `PositionIntent` and
`TacticalTask`/`NativePositionAction`.)

A child level gets the wanted result and its limits. It reports `Accepted`, `Running`,
`Suspended(reason)`, `Completed(evidence)`, `Failed(reason)` or `Cancelled`. When finished with part
of its members, the report lists who took part and who dropped out; whether partial success is
acceptable is up to the parent task.

Recomputing the same assignment keeps its identity and execution. Changing the overall formation
does not reset every search. An executor reviews only its own task's dependencies: a changed goal or
area, danger at the chosen position, a significant change in observations or path availability.
Refreshing an observation's time without changing anything that matters must not reset the search.

Waiting for search budget, exhausting the area searched, one path failing and the whole manoeuvre
being impossible are different results. A search that found no point within 24 blocks reports the
area and conditions of the attempt; it does not follow that the order cannot be carried out. The
parent chooses a new approach, another role, or ending with a refusal.

One NPC executor arbitrates its resources explicitly: movement, look and aim, weapon and inventory,
pose, vehicle seat. Existing SmartBrainLib behaviours stay as execution adapters. Grenades, healing,
resupply and boarding have explicit compatibility and interruption rules. A side call into navigation
by another behaviour must not silently take the route away.

Navigation and checks of cover, safe fire and reachability are services with query parameters. They
do not choose a strategy and do not branch on its class. Every real action uses the same physical
checks and shared budget; a strategy cannot declare an unsafe shot allowed.

## Updates, saving and diagnostics

Decisions use explicitly dated snapshots. The order of handling events, running actions and receiving
results is set centrally, so different NPCs do not recompute one squad independently. Rates and
budgets come from measurements, not one value for all five levels.

Player-made squads, current orders, authority and lasting game rules are saved. Paths, search cursors
and short-lived commitments are normally rebuilt after loading from the data available. An unloaded
entity counts as unavailable, not dead. World time and individual NPCs' clocks are not
interchangeable.

Each NPC has an explanation chain: original order → operation → squad role → team subtask → local
task → action or reason for waiting. Each decision records the facts it used, ids, the reason for a
review and the result. The pure part of decisions can be replayed from saved inputs; a physical
Minecraft fight is not claimed to be bit-for-bit deterministic.

## Proposed first scenarios

| Scenario | What the architecture must show |
|---|---|
| A MOVE meets the enemy | The reaction does not lose the original order; the continue/pause rules can be explained |
| DEFEND, enemy above or to the side | Contact, height and order are taken into account separately; leaving the area needs an allowed reason |
| Two squads attack one area | The group assigns roles; squads and teams keep local autonomy |
| One flank is unreachable | A specific refusal comes back; independent actions go on, dependent ones are reviewed |
| 2×32 against 16, narrow and wide front | Scaling members and geometry keeps execution working; support and lookouts have useful bounded roles |
| Enemy behind a ridge, no local firing posts | The local executor reports the area exhausted; its owner chooses the next attempt |
| A threat from behind during a flank | The NPC reacts to local danger; the consequences for the subtask and operation go up |
| A medic heals, an ally covers, ammunition runs out | Resources and priorities are agreed; nobody silently takes another's navigation or fire |
| A new order, losses, reinforcement, a chunk unloads | Cancellation and changes of members release only the affected tasks' resources; stale results are ignored |
| A helicopter attacks and then lands / delivers troops | The air executor uses the shared result contract, its own capabilities and explicit landing stages |
| The player and Commander AI give the same order | With the same rights and inputs the squad runs the same logic; a conflict between sources is resolved explicitly |

The scenarios define architectural contracts. Participation numbers, delays, withdrawal balance and
lookout sizes are discussed separately. Useful fresh support and reasoned waiting need not mean every
man firing. This is not permission to weaken accepted tests to get a green result.

## How to check the design and move the system over

1. Agree on examples, authority and definitions of success and failure at each level.
2. Write out the protocol of one full scenario: two squads attack an area, one flank is unreachable,
   the second has enough capability to carry on.
3. Check extensibility on paper: new geometry, attacking a height and a transport subtask must fit
   in their own boundaries without changing shared navigation and fire.
4. After a separate decision to implement, build an isolated variant and the minimal passage of an
   order through all levels. Prove ownership, feedback and interruptions first; then the quality of
   specific tactics.
5. Move existing abilities through adapters. One NPC or squad has one execution owner in the active
   variant; the old and new controllers never run at once.
6. Keep the original problem battle and the independent checks of physics, safety, the medic's role,
   vehicles and budgets. Architectural unit tests and real fights are different kinds of evidence.

Measure of improvement: reasons for waiting are visible, execution is not reset without a
significant reason, a level's error returns to its owner, a new strategy needs no new exceptions in
shared executors. A big diagram and the number of new classes are not the result.

## Creating an operation and joining late

A shared order to several selected squads gets one operation id and separate execution ids for the
members. Authority, alliance of all factions and the ability to carry out the order are checked
atomically first; if the selection is not allowed, the order goes to nobody. A temporary truce is not
an alliance: `Diplomacy.allied` and `Diplomacy.friendly` tell these apart.

Proposal for points: the goal key holds the world dimension, the order type and the target area; an
entity or building uses a stable target id. The same block is an exact match. Neighbouring clicks
match within an explicit small tolerance inside the operation's area, not by searching for the
nearest fight. The tolerance is not agreed yet. ATTACK and DEFEND at the same point are not merged
into one order automatically.

A single squad also has an execution context. It can become the core of an operation a second squad
joins later. If several existing operations fit, an exact goal match and compatibility come first;
the choice is fixed before arrival so the squad does not jump between operations every tick.

A late reinforcement is "on its way" with a conditional link to the operation. It makes its own
approach and local reactions and gets the information it is allowed, but it does not count as an
arrived member and gives no cover by assignment alone. On reaching a safe entry area near the goal
and confirming it can fight, it joins; it need not walk straight into the enemy's point. The entry
area is an explicit operation parameter and is checked physically.

Adding a member opens new capacity and roles while keeping untouched tasks. Changing its order
removes the link. A completed or cancelled operation does not accept members: a late order gets its
own execution. A change in diplomacy withdraws incompatible assignments and is also handled by the
safety system.

## Commander AI: army, resources and territory

Commander AI has a stable `commanderId`, a faction, a home point, a character profile, a resource
wallet, a reserve and a list of the squads it owns. Its physical entity and its strategic controller
are separate responsibilities; what happens when the entity dies is still to be decided.

The strategic loop: assess known threats and territory → compare goals → assign available squads →
plan production and reinforcement → give ordinary orders → watch the results. Example goals: defend
the base, take a nearby barracks or Supply, reinforce a front, build a reserve. Detailed joint
tactics belong to operations; choosing each NPC's place belongs to the lower levels.

Character sets preferences: aggression, preserving the army, appetite for risk and reserve size. It
affects how allowed actions are scored. Safe fire, physical reachability and diplomacy rules hold
for every character.

The economy takes events of confirmed kills and of taking and holding objectives. The owner of
resources is separate from the faction; two Commanders of one faction must not share one wallet by
accident. Before implementing, decide the reward for taking and the income for holding, credit for
kills, the cost of spawning squads and the reinforcement model. Economic event ids prevent paying a
reward twice after a repeated delivery or a reload. Spending and successfully creating units are
committed together, with the reserved amount returned if creation fails.

Territory proposal: expand through reachable neighbouring parcels from the home area, keeping
connected to held territory. Distance affects a goal's cost and appeal; an outer limit is set
separately. This expresses "expands from where it was placed" better than picking any profitable
target on the whole map. The parcel model, maximum range and supply rules are still under
discussion. The Commander uses the information it is allowed, not a hidden full list of enemies.

The Commander, economy, production strategy and territory are part of the target architecture now.
They can be built and checked in stages on the shared foundation, without replacing a temporary
controller with a full one later.

## Teams for subtasks

A team is formed from suitable members for the function needed: fire support, advance, a side
approach, guarding a sector or helping a medic. Its size follows the task and the real capacity of
the terrain. The number six from an earlier experiment is a default, not an architectural rule.

During one subtask the team's identity and members' assignments are kept. When someone drops out,
the needed vacancies are filled first; a mass reshuffle is allowed only after a significant change in
capability. Reinforcements take useful free roles first. The share of losses and the reinforcement
threshold for reassignment will be parameters. Healing and other important local work are taken
into account when choosing available members.

A change of members affects only the related tasks and commitments. Death, temporary unavailability
and unloading are told apart. Having no member able to fight makes a subtask impossible, not done.

## Proposal: completing an area ATTACK

Moving to holding after an attack is a stage of execution with the original `orderId`. A separate
manual DEFEND closes or replaces the ATTACK and does not give it "success" automatically.

Proposed protocol: Approach → Engage/Clear → Secure → Completed. Stages can repeat when contact
appears; taking part in a firefight is not needed to occupy an empty area.

The Secure timer starts when the assigned members have physically occupied the reachable target
area, checked the local sectors needed and can hold it. Simply losing sight of the target is not
enough. While there is a current report of a threat inside the area, an unchecked significant last
enemy position or relevant incoming fire, holding is not confirmed. Searching for a hidden enemy uses
the last observation and the terrain available.

Starting proposal: 30 seconds of game time of continuous confirmed holding (implemented as 600
ticks). A new relevant contact or incoming fire resets the timer; temporary unavailability of the
needed observers or the area pauses the check and requires confirmation again before carrying on. The
timer cannot run on the last known absence of threats after a chunk unloads.

At the end, an area ATTACK gets the result "area occupied and held", and the members carry on with
DEFEND under the completion policy. That does not claim every enemy is dead. Taking a Barracks or
Supply also needs confirmation of the ownership mechanic. An attack on a specific entity uses its own
criteria: the target's confirmed death or destruction, or a lost-target result, not success after a
spell without contact.

An unreachable area, a long time without progress and being unable to hold the target give an
explainable refusal or pause. The 30 seconds is a starting parameter to check, not the result of a
balance study.

## Proposal: risk and withdrawal

Hard safety and authority limits are separate from acceptable risk. Risk steers the choice between
allowed ways: a long flank, a quick approach under cover, waiting for reinforcements, regrouping or
calling off the attack. A profile cannot allow shooting allies or treat an unknown path as
reachable.

A balanced profile is proposed to start with. Guides for testing: about 25% confirmed losses of the
original fighting strength call for a review of the plan; about 40% together with an ongoing threat
and no way to carry on lean towards an organised withdrawal. That is not an unconditional "40% = run"
regardless of the ground already taken or the enemy's state. Suppression, health, ammunition, nearby
reinforcements and an available way out are weighed together. (Implemented: review at 30% and
withdrawal at 60% of the operation's strength, see the status table.)

Losses are counted per execution epoch of the operation and are not reset on each review of the plan.
Reinforcement adds capability but does not erase the history of the dead. An aggressive Commander
values progress more, a cautious one preserving its strength; the critical limits stay the same.

The existing ammunition rules, the manual repeat of an ATTACK after a refusal and the priority of
medical help stay until a change is agreed separately. The new architecture first expresses them
explicitly rather than replacing them with arbitrary new percentages.

## Proposal: choosing strategies and plans

Utility's contracts and the limits of its use are described in [UTILITY-DESIGN.md](UTILITY-DESIGN.md).

Three mechanisms with different duties are combined:

| Mechanism | Where | What it does |
|---|---|---|
| Explicit mandatory rules | All levels | Authority, safety, immediate reactions, completion and refusal criteria |
| Utility scoring of allowed options | Commander, operation, squad | Compares goals and ways by expected benefit, cost, risk, terrain and capabilities |
| A shared HTN core with task domains | The decision levels, at their scale | Breaks goals into subtasks, chooses allowed alternative methods and returns a plan to execute |

A full shared HTN core and task domains are designed at once. A new manoeuvre is described by tasks,
methods and conditions in its domain; it needs no new queueing, cancellation or recovery mechanism
and no new exceptions in navigation. On the NPC, existing SmartBrainLib behaviours serve as adapters
for concrete actions and resources. The choice of method, the planning state and the physical
execution state have different owners.

The target foundation includes:

- compound and executable tasks, alternative decomposition methods, typed parameters and limits;
- start, continuation, success and failure conditions; dependencies on specific facts;
- linking parent tasks to child assignments across the five levels;
- shared execution of plans with dependencies, joint and independent actions, and confirmation of
  cover and other commitments;
- resource ownership, accepting and declining assignments, interruptions, cancellation, recovery and
  partial review that keeps untouched progress;
- planning within a budget, continuing a search between ticks, limiting repeated failures and
  guarding against circular waits;
- tracing the methods chosen and rejected, the facts used, plan changes and results;
- versions of saved data, recovery after loading, and checking extensibility for vehicles, supply
  and Commander AI.

Parallel physical execution, resource management and agreements between squads belong to the runtime
and coordination; HTN alone does not provide them. Effects assumed while planning do not become
observed facts: arrival, fire, spending and capture are confirmed by execution.

Staged checks go through the target contracts of this core. What can be limited is how many domain
rules are added at once and the depth and time of search; those limits do not replace the shared
mechanism with temporary special cases. An early load experiment checks whether the requirements can
be met, not a cut-down product.

Utility example: an operation compares frontal pressure, a left flank and a right flank. Unreachable
or forbidden options are excluded before comparing. The rest get scores with explainable factors. A
new option winning does not cancel the current plan on every small change: it needs a clear
advantage, a minimum stability of the decision and a safe switching point; a significant new danger
is handled at once.

[An Introduction to Utility Theory, David Graham](https://www.gameaipro.com/GameAIPro/GameAIPro_Chapter09_An_Introduction_to_Utility_Theory.pdf)
covers scoring actions, preference factors and stable choices.
[Guerrilla's material on HTN in Decima](https://www.guerrilla-games.com/read/from-byrd-box-to-debug-boxes-htn-introduction-and-application-in-decima)
is an example of hierarchical planning in production. The combination proposed here is a design
decision for this project, still being checked by scenarios.

## Performance: 150 visible, 600 active

These are two different checks: the client drawing, animating and showing effects for 150 NPCs, and
the server observing, simulating, shooting, pathing and deciding for 600 active entities. They must
also be checked together in one world. Lowering the rate of expensive decisions does not mean the
other 450 NPCs stop living: they keep moving, fighting and carrying out tasks.

Proposed means: shared spatial queries for candidates, one situation snapshot per squad or operation,
caching unchanged queries, spreading searches over ticks, bounded queues that prevent starvation and
put immediate danger first. A shared candidate list does not replace each NPC's own visibility check.
Expensive searches need a budget; safe actions and paths already set go on while a search waits.

Starting intervals to measure: Commander strategic decisions every 1–2 seconds, an ordinary operation
review every 0.5–1 second, a squad every 0.25–0.5 seconds. Critical events wake the affected level
earlier; events are coalesced so a 600-NPC fight does not cause 600 full recomputations in one tick.
These intervals do not set the rate of physics or of the immediate action's safety checks.

Proposed server target: 20 TPS, p95 full tick under 50 ms on the target laptop; averages and p99 are
recorded too. The client FPS target and the laptop's specs are not known yet. AI budgets are set by
measuring what is left of the full tick, including Minecraft, SBW, projectiles and other mods; limits
on A* calls alone are not enough.

An early load prototype comes before moving all tactics over: 600 NPCs waiting and moving, then
spread-out active fights, then 150 in view in the same world, then mass simultaneous order and
terrain changes. Queues, worst reaction delays, navigation, projectiles, memory and the client frame
are measured. An earlier headless check of 128 NPCs does not prove 600 and does not measure client
FPS.

Truly active distant NPCs need their entities and chunks ticking. The loading model, the radius limit
and the cost of chunk tickets need a separate decision. Simulating fight results numerically in
unloaded chunks does not count as meeting the 600-active requirement.

## Open questions

- What is the "same point" tolerance and the safe entry area of an operation? What is shown for an
  incompatible selection?
- Can one joint order include infantry, tanks and helicopters with different ways of carrying it
  out? Proposal: yes, with capability checks and separate criteria for their participation.
- What happens to the army and resources when the Commander's physical entity dies? Can a faction
  have several Commanders?
- How are resources earned for kills, taking and holding objectives; how do production and
  reinforcement work?
- What is the outer limit of territorial expansion, and what routes and supply link it to the home
  area?
- Do the proposed 30-second Secure, the risk principles and the combination of utility with task
  schemes fit?
- What are the laptop's CPU/GPU/RAM, the mod set and the wanted minimum FPS with 150 NPCs in view?
