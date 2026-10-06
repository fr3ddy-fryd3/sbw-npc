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
            assertTrue(plan.behavior.extendsPreparation(now))
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
        assertFalse(plan.behavior.extendsPreparation(100))
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
}
