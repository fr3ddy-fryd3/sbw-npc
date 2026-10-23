# HTN diagrams

How the tactical HTN in `common/.../ai/tactics` and `ai/integration` is built today, drawn by hand
from the code. It is a map for discussing and planning changes, so keep it in step with the code:
a change to `TacticalDomain`, `OperationTactics.duty`, `SquadStrategy` or `AttackPhase` updates the
diagram it touches in the same PR. The prose around the code is in
[ARCHITECTURE.md](ARCHITECTURE.md#squad-tactics-htn) and [AI-RUNTIME.md](AI-RUNTIME.md).

## Levels

Every level is a planning agent with its own `HtnDomain` (`TacticalDomain.at`). A level hands its
children their tasks through `DelegationTask`; only the NPC level runs a world action.
`NativeTactics` builds the missions from squad snapshots and runs the agents.

```mermaid
flowchart TD
    NT["NativeTactics<br/>squad snapshots, duties, intents"] --> OP
    OP["OPERATION<br/>operation.execute (OperationMission)"] -->|"delegate per squad,<br/>roles applied: OperationTactics.apply"| SQ
    OP -->|"secure task, if the mission has one"| SEC["operation.hold_and_inspect<br/>action operation.secure_area<br/>done: AREA_SECURED"]
    SQ["SQUAD<br/>squad.execute (SquadMission)"] -->|"delegate per team,<br/>intents shaped by the strategy"| TM
    TM["TEAM<br/>team.execute (TeamMission)"] -->|"delegate per member"| NPC
    NPC["NPC<br/>npc.execute (PositionIntent)"] --> POS["npc.post<br/>action npc.position<br/>NativePositionAction"]
```

## Operation

One method per attack phase, chosen by the `ATTACK_PHASE` fact; with no phase the squads carry out
their own missions.

```mermaid
flowchart TD
    E["operation.execute"] --> C{"ATTACK_PHASE?"}
    C -->|none| CO["coordinate<br/>squads keep their own intents"]
    C -->|DEPLOY| D["deploy"]
    C -->|ASSAULT| A["assault"]
    C -->|CLEAR| CL["clear"]
    D & A & CL --> R["OperationTactics.apply(roles)"]
    R --> R1["clearing man: FIRE from a post short of his target"]
    R --> R2["assault slot: MOVE to the slot"]
    R --> R3["closing post: FIRE from the post"]
    R --> R4["OBSERVE, SEARCH, FOLLOW_ORDER: unchanged<br/>REAR_GUARD joins the roles like MOVE and FIRE"]
```

`AttackPhase.next` moves between the phases. Its first check runs in every phase: with the
objective occupied and the attack not losing, the phase is CLEAR while threats are nearby and
ASSAULT otherwise. Losing means a contact in the last 200 ticks and fewer than 1.5 ready shooters
per enemy at the objective.

```mermaid
stateDiagram-v2
    [*] --> DEPLOY
    DEPLOY --> ASSAULT: quiet 200 ticks, or assaultable
    ASSAULT --> DEPLOY: losing
    CLEAR --> DEPLOY: losing
    CLEAR --> CLEAR: threats nearby
    CLEAR --> ASSAULT: no threats nearby
    ASSAULT --> CLEAR: objective occupied, threats nearby
    DEPLOY --> CLEAR: objective occupied, threats nearby
    DEPLOY --> ASSAULT: objective occupied, no threats
```

## Squad

One method per `CombatStrategy`, chosen by the `STRATEGY` fact. `StrategyEvaluation` scores the
allowed strategies and `DecisionStability` keeps the current one for at least 40 ticks, and after
that until another scores 0.12 higher. The strategy reshapes each member's intent, except an
assaulting or ordered one.

```mermaid
flowchart TD
    S["squad.execute"] --> ST{"STRATEGY?"}
    ST --> ADV["advance, 0.55 + 0.2 x ready share<br/>allowed: offensive and can go on, or no contact<br/>intent unchanged"]
    ST --> HOLD["hold, 0.9 in defence under contact, else 0.4<br/>always allowed<br/>MOVE becomes FIRE where he stands, no cover required"]
    ST --> GH["gain_height, 0.93<br/>allowed: contact, enemy above, offensive<br/>posts no lower than 2 below him, higher preferred"]
    ST --> WD["withdraw, 0.95<br/>allowed: incoming fire, 40% lost, no continuation, no barracks<br/>MOVE 48 blocks away from the threat, no height floor<br/>FOLLOW_ORDER unchanged"]
```

## Member duty

`OperationTactics.duty` picks each member's duty before the plan; the first matching row wins.

```mermaid
flowchart TD
    A{"objective in another dimension?"} -->|yes| FO1["FOLLOW_ORDER"]
    A -->|no| B{"scout on ATTACK?"}
    B -->|yes| SE["SEARCH"]
    B -->|no| C{"DEFEND or PATROL without route,<br/>machine gunner or sniper with ammo,<br/>not following a player?"}
    C -->|yes| OB["OBSERVE<br/>height post round the objective"]
    C -->|no| D{"no contact, or RETREAT / MOVE / BARRAGE?"}
    D -->|yes| FO2["FOLLOW_ORDER"]
    D -->|no| F{"following a player"}
    F -->|"player in a vehicle, or he holds one"| FO3["FOLLOW_ORDER"]
    F -->|"bounding half with a slot"| MV1["MOVE to the slot"]
    F -->|no| G{"order is not ATTACK?"}
    G -->|yes| FI1["FIRE where he stands"]
    G -->|no| H{"flanking?"}
    H -->|yes| MV2["MOVE"]
    H -->|no| I{"front's proposal"}
    I -->|"MOVE, and reached, fired or within 8 blocks"| FI2["FIRE"]
    I -->|"MOVE, otherwise"| MV3["MOVE"]
    I -->|"FIRE at a post beyond fire reach"| MV4["MOVE to short of the post"]
    I -->|"anything else"| PR["the proposal as it is"]
```

## NPC

The NPC domain finds a post for the intent. When the search fails, `NativeTactics` records a
`POST_FAILURE` for the anchor, valid for 200 ticks, and the next plan tries the next method.

```mermaid
flowchart TD
    N["npc.execute (PositionIntent)"] --> M1{"preferred_post:<br/>no failure at this anchor"}
    M1 -->|allowed| P["npc.post at the anchor"]
    M1 -->|rejected| M2{"neighbor_post:<br/>failed up to 4 times"}
    M2 -->|allowed| P2["npc.post 12 blocks aside, forward or back"]
    M2 -->|rejected| M3{"closer_post:<br/>FIRE or MOVE, mostly blind shots,<br/>steps left"}
    M3 -->|allowed| P3["npc.post nearer the focus,<br/>never inside the standoff"]
    M3 -->|rejected| X["no plan: he stays where he is"]
```

`npc.post` runs the `npc.position` action. It holds while the man may move (`MOVABLE`) and, when
cover is required, while he has it (`COVER`); it is done on `ARRIVED`. FOLLOW_ORDER takes no `motion`
resource and never times out; every other duty holds `motion` and times out after 1200 ticks. `NativePositionAction` turns the duty into the job the
position search in `combat/tactics` runs:

| Duty | Job |
|---|---|
| FIRE | ANTI_ARMOUR when he carries a loaded launcher and armour is in sight, otherwise COVER |
| MOVE | ADVANCE |
| SEARCH | SEARCH |
| REAR_GUARD | RESERVE |
| OBSERVE | OVERWATCH with a height floor, otherwise OBSERVE |
| FOLLOW_ORDER | WAIT |
