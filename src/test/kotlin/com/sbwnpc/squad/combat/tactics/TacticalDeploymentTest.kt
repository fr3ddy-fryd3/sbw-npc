package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalDeploymentTest {
    private val squad = UUID(0, 99)
    private val origin = Vec3(0.0, 64.0, 0.0)
    private val enemy = Vec3(200.0, 64.0, 0.0)
    private fun view() =
        TacticalSnapshot(
            0,
            SquadOrder.ATTACK,
            1,
            origin,
            enemy,
            (0 until 32).map { TacticalMember(UUID(0, it.toLong()), origin) },
            listOf(TacticalContact(UUID(1, 1), enemy, 0)),
        )

    @Test fun `reported movement towards the front preserves the same plan and assignments across assessments`() {
        var proposal = TacticalChoice(TacticalPattern.FLANK, enemy)
        val state = SquadTacticalState(decisions = TacticalDecisionPolicy { proposal })
        val initial = view()
        state.assess(squad, initial)
        val plan = state.plan!!
        val assignments = plan.tasks.toMap()
        proposal = TacticalChoice(TacticalPattern.FOCUS_SECTOR, enemy)
        for (now in 80L..480L step 80) {
            val moving = initial.copy(
                now = now,
                members = initial.members.mapIndexed { index, member ->
                    // Input telemetry for a six-block line; do not derive the expected motion from the planner.
                    val column = (index - 15.5) * 6.0
                    val travelled = minOf(kotlin.math.abs(column), now / 10.0)
                    member.copy(position = origin.add(0.0, 0.0, kotlin.math.sign(column) * travelled), moving = true)
                },
            )
            state.assess(squad, moving)
            assertSame(plan, state.plan)
            assertEquals(TacticalPhase.PREPARING, plan.phase)
            assertTrue(plan.behavior.ownsPhaseDeadline())
            assignments.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        }
        assertEquals(0, (plan.behavior as CoveredManeuverState).laneAttempts)
    }

    @Test fun `a moving flag without coordinate progress cannot postpone opening a firing lane`() {
        val state = SquadTacticalState(decisions = TacticalDecisionPolicy { TacticalChoice(TacticalPattern.FLANK, enemy) })
        val initial = view().copy(members = view().members.map { it.copy(moving = true) })
        state.assess(squad, initial)
        val plan = state.plan!!
        for (now in 10L..90L step 10) {
            state.assess(squad, initial.copy(now = now))
            assertSame(plan, state.plan)
            assertEquals(TacticalPhase.PREPARING, plan.phase)
        }
        state.assess(squad, initial.copy(now = 100))
        assertSame(plan, state.plan)
        assertEquals(TacticalPhase.OPENING_LANE, plan.phase)
        assertFalse(plan.behavior.ownsPhaseDeadline())
    }

    @Test fun `deployment progress has a hard deadline even if one member moves a little`() {
        val plan = TacticalPlan(1, TacticalPattern.FLANK, enemy, 0, 1)
        val initial = view()
        TacticalCoordinator.assign(squad, plan, initial)
        val deployment = TacticalDeployment()
        for (now in 0L..560L step 80) {
            deployment.observe(
                plan,
                initial.copy(
                    now = now,
                    members = initial.members.mapIndexed { index, member ->
                        if (index == 0) member.copy(position = origin.add(0.0, 0.0, -now / 40.0)) else member
                    },
                ),
            )
            assertTrue(deployment.progressing(now), "Progress must still extend preparation at tick $now")
        }
        assertTrue(deployment.progressing(599))
        assertFalse(deployment.progressing(600))
    }

    @Test fun `two settled shooters cannot start the maneuver while the remaining line is deploying`() {
        val state = SquadTacticalState(decisions = TacticalDecisionPolicy { TacticalChoice(TacticalPattern.FLANK, enemy) })
        val initial = view()
        state.assess(squad, initial)
        val plan = state.plan!!
        val preparing = initial.copy(
            now = 10,
            members = initial.members.mapIndexed { index, member ->
                if (index == 15 || index == 16) {
                    member.copy(
                        position = origin.add(0.0, 0.0, (index - 15.5) * 6.0),
                        canFire = true,
                        firingAt = enemy,
                        recentFire = true,
                        recentFireAt = enemy,
                    )
                } else {
                    member.copy(moving = true)
                }
            },
        )
        state.assess(squad, preparing)
        assertSame(plan, state.plan)
        assertEquals(TacticalPhase.PREPARING, plan.phase)

        val deployed = preparing.copy(
            now = 90,
            members = preparing.members.mapIndexed { index, member ->
                member.copy(position = origin.add(0.0, 0.0, (index - 15.5) * 6.0), moving = false)
            },
        )
        state.assess(squad, deployed)
        assertSame(plan, state.plan)
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        state.assess(squad, deployed.copy(now = 129))
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        state.assess(squad, deployed.copy(now = 130))
        assertEquals(TacticalPhase.OPENING_LANE, plan.phase)
        assertEquals(6, plan.tasks.values.count { it.opensLane })
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.FLANK })
    }

    @Test fun `normal assessment lets late height deployment finish readiness before replacing the plan`() {
        val focus = enemy.add(0.0, 30.0, 0.0)
        val initial = view().copy(members = view().members.take(8), contacts = listOf(TacticalContact(UUID(1, 1), focus, 0)))
        val state = SquadTacticalState(decisions = TacticalDecisionPolicy { TacticalChoice(TacticalPattern.ATTACK_HEIGHT, focus) })
        state.assess(squad, initial)
        val plan = state.plan!!
        fun deployedAt(now: Long, fraction: Double) =
            initial.copy(
                now = now,
                contacts = initial.contacts.map { it.copy(seenAt = now) },
                members = initial.members.mapIndexed { index, member ->
                    member.copy(position = origin.add(0.0, 0.0, (index - 3.5) * 6.0 * fraction))
                },
            )
        for (now in 80L..400L step 80) state.assess(squad, deployedAt(now, now / 440.0))
        state.assess(squad, deployedAt(440, 1.0))
        state.assess(squad, deployedAt(480, 1.0))
        assertSame(plan, state.plan, "The selection deadline must respect the state's bounded readiness wait")
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        state.assess(squad, deployedAt(580, 1.0))
        assertSame(plan, state.plan)
        state.assess(squad, deployedAt(590, 1.0))
        assertEquals(TacticalPhase.FAILED, plan.phase)
        assertEquals(TacticalFailure.COVER_NOT_READY_TIMEOUT, plan.failure)
    }

    @Test fun `cover readiness gets its bounded wait after deployment and settling`() {
        val focus = enemy.add(0.0, 8.0, 0.0)
        val initial = view().copy(members = view().members.take(8), contacts = listOf(TacticalContact(UUID(1, 1), focus, 0)))
        val state = SquadTacticalState(decisions = TacticalDecisionPolicy { TacticalChoice(TacticalPattern.ATTACK_HEIGHT, focus) })
        state.assess(squad, initial)
        val plan = state.plan!!
        val deployed = initial.copy(
            now = 150,
            members = initial.members.mapIndexed { index, member -> member.copy(position = origin.add(0.0, 0.0, (index - 3.5) * 6.0)) },
            contacts = initial.contacts.map { it.copy(seenAt = 150) },
        )
        TacticalCoordinator.advance(squad, state, plan, deployed)
        TacticalCoordinator.advance(squad, state, plan, deployed.copy(now = 190, contacts = initial.contacts.map { it.copy(seenAt = 190) }))
        assertEquals(TacticalPhase.PREPARING, plan.phase, "Deployment must not exhaust the readiness wait")

        val ready = deployed.copy(
            now = 220,
            members = deployed.members.map { member ->
                plan.tasks[member.id]?.let { it.position = member.position }
                member.copy(canFire = plan.tasks[member.id]?.job == TacticalJob.COVER, firingAt = focus)
            },
            contacts = initial.contacts.map { it.copy(seenAt = 220) },
        )
        TacticalCoordinator.advance(squad, state, plan, ready)
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.ADVANCE })
    }
}
