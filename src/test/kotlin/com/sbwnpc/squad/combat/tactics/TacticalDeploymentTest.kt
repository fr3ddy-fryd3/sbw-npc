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

    @Test fun `line deployment preserves preparation while members actually approach their slots`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
        val plan = state.plan!!
        val initial = view()
        TacticalCoordinator.assign(squad, plan, initial, state)
        TacticalCoordinator.advance(squad, state, plan, initial)
        for (now in 80L..480L step 80) {
            val moving = initial.copy(
                now = now,
                members = initial.members.map { member ->
                    val post = plan.tasks[member.id]!!.frontage!!.point
                    member.copy(position = TacticalLayout.towards(origin, post, now / 10.0), moving = true)
                },
            )
            TacticalCoordinator.advance(squad, state, plan, moving)
            assertEquals(TacticalPhase.PREPARING, plan.phase)
            assertTrue(plan.behavior.extendsPreparation(now))
            assertTrue(TacticalSelectionPolicy.evaluate(plan, TacticalChoice(TacticalPattern.FOCUS_SECTOR, enemy), 1, now, TacticalStates.registry) is TacticalSelection.Keep)
        }
        assertEquals(0, (plan.behavior as CoveredManeuverState).laneAttempts)
    }

    @Test fun `an immobile deployment still opens a firing lane rather than waiting forever`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
        val plan = state.plan!!
        val initial = view()
        TacticalCoordinator.assign(squad, plan, initial, state)
        TacticalCoordinator.advance(squad, state, plan, initial)
        TacticalCoordinator.advance(squad, state, plan, initial.copy(now = 100))
        assertEquals(TacticalPhase.OPENING_LANE, plan.phase)
        assertFalse(plan.behavior.extendsPreparation(100))
    }

    @Test fun `deployment progress has a hard deadline even if one member moves a little`() {
        val plan = TacticalPlan(1, TacticalPattern.FLANK, enemy, 0, 1)
        val initial = view()
        TacticalCoordinator.assign(squad, plan, initial)
        val deployment = TacticalDeployment()
        for (now in 0L..600L step 80) {
            deployment.observe(
                plan,
                initial.copy(
                    now = now,
                    members = initial.members.map { member ->
                        member.copy(position = TacticalLayout.towards(origin, plan.tasks[member.id]!!.frontage!!.point, now / 40.0))
                    },
                ),
            )
        }
        assertFalse(deployment.progressing(600))
    }
}
