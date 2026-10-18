# Tactics tests

| What is checked | Files | Boundary |
|---|---|---|
| HTN: tasks, planning, execution, operations, attack phases and roles, members' duties, losses, area control | `common/src/test/kotlin/com/sbwnpc/squad/ai/` (`planning/`, `coordination/`, `tactics/`) | Pure modules without Minecraft; the world, navigation and weapons take no part |
| Firing requirements at the stages of a search | `TacticalDeploymentLaneTest.kt` | Choice of a firing post in its strip; no movement or fire in the world |
| Cover, exposure and cost priority | `TacticalPositionScoreTest.kt`, `TacticalSearchBatchTest.kt` | Order of score comparison and batching; cover and reachability are not checked |
| Protected high posts | `DefensiveOverwatchTest.kt` | Parapet, body cover and the firing lane over it in a given `BlockGetter` |
| Ground surface and rays | `TacticalGroundTest.kt`, `DetectionSightlineTest.kt`, `SightlineTest.kt` | Real Minecraft collision shapes and voxel traversal in a given `BlockGetter`; no server entities are updated |
| Path end, budget, legs and route checks | `TacticalPathTest.kt`, `TacticalPathBudgetTest.kt`, `TacticalRouteLegTest.kt`, `TacticalRouteValidationTest.kt` | Checks of a given `Path` object and queue; no path is built |
| Aim, clearance, goal progress, target choice | `TacticalAimTest.kt`, `TacticalClearanceTest.kt`, `TacticalGoalProgressTest.kt`, `TacticalTargetSelectionTest.kt` | Geometry and selection over given snapshots |
| A single MOVE and climbing a full block | `neoforge/src/gameTest/.../InfantryMovementGameTests.kt` | Real NPCs, server, brain, collisions and navigation. How to run: [HEADLESS-TESTS.md](HEADLESS-TESTS.md) |
| Joint attack, capture, reinforcement, water, withdrawal on losses | `neoforge/src/gameTest/.../HtnTacticsGameTests.kt` | Real squads under the HTN, real paths and shots |
| Deployment and fire in the open | `neoforge/src/gameTest/.../AttackDeploymentGameTests.kt` | 16 real riflemen, real SBW bullets, the physical line and damage sources; targets without AI |

Main directory: [common/src/test/kotlin/com/sbwnpc/squad/combat/tactics/](../common/src/test/kotlin/com/sbwnpc/squad/combat/tactics/).
Ground, ray and arrival checks are one level up, in [combat/](../common/src/test/kotlin/com/sbwnpc/squad/combat/).
Tests that need the block registry bootstrapped (overwatch cover, tactical ground, sightlines) stay in
[neoforge/src/test/kotlin/com/sbwnpc/squad/combat/](../neoforge/src/test/kotlin/com/sbwnpc/squad/combat/).
The tests of the former tactics controller were removed with it.

## Changing tests

- A test's name says what it observes. A score comparison is not called a cover search; a flag
  change is not called movement.
- A repeated condition stays if it checks a different boundary, for example computing a timer and
  the controller using it. Identical transitions with identical expectations are merged, keeping
  any extra conditions.
- A deadline check first confirms the working behaviour up to the boundary, then the stop at it.
  Otherwise a permanent refusal can look like a pass.
- For a fixed defect, check that the scene is sensitive to it: bringing the bug back must fail a
  specific assertion, not compilation or startup.

## Running and reports

Full run: `./gradlew test` (both modules). One class:
`./gradlew :neoforge:test --tests 'com.sbwnpc.squad.combat.tactics.TacticalPositionScoreTest'`.

Results: `<module>/build/reports/tests/test/` and `<module>/build/test-results/test/`. Passing
geometry and state checks do not prove the whole fight works. Reachability in the world, weapons
and cover behaviour working together, bunching under fire and the outcome of an attack are
checked in game, from observations and logs.
