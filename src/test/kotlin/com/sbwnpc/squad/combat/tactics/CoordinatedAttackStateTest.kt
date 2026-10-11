package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.tactics.operation.*
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CoordinatedAttackStateTest {
    private val focus = Vec3(120.0, 64.0, 0.0)
    private fun view(role: AttackRole = AttackRole.CENTER): TacticalSnapshot {
        val members = (0..7).map { TacticalMember(UUID(0, it.toLong()), Vec3(0.0, 64.0, (it - 3.5) * 6.0)) }
        val assignment = AttackAssignment(1, role, AttackStage.DEPLOYING, 0, Vec3(24.0, 64.0, 0.0), Vec3(48.0, 64.0, 0.0), Vec3(1.0, 0.0, 0.0), AttackCorridor(listOf(Vec3.ZERO), 200.0), false, false, false, members.associate { it.id to it.position.z })
        return TacticalSnapshot(0, SquadOrder.ATTACK, 1, Vec3(0.0, 64.0, 0.0), focus, members, listOf(TacticalContact(UUID(1, 1), focus, 0)), attackAssignment = assignment)
    }

    @Test fun `partial wave keeps unfinished native search while the next group starts`() {
        val base = view()
        val initial = base.copy(incoming = listOf(focus), attackAssignment = base.attackAssignment!!.copy(externalCover = true))
        val squad = UUID(3, 3)
        val owner = SquadTacticalState()
        val plan = TacticalPlan(1, TacticalPattern.COORDINATED_ATTACK, focus, 0, 1, TacticalStatus.EXECUTING)
        plan.assign(squad, initial, owner)
        plan.tick(squad, owner, initial)
        val movers = initial.members.filter { plan.tasks[it.id]?.job == TacticalJob.ADVANCE }
        assertEquals(4, movers.size)
        val laggard = movers.last()
        val unfinished = plan.tasks.getValue(laggard.id)
        val search = TacticalPositionSearch(laggard.position, listOf(unfinished.anchor)).apply { probeIndex = 1 }
        unfinished.search = search
        val advanced = initial.copy(
            now = 20,
            members = initial.members.map { member ->
                if (member in movers.dropLast(1)) {
                    val task = plan.tasks.getValue(member.id)
                    task.position = task.anchor
                    member.copy(position = task.anchor)
                } else {
                    member
                }
            },
        )
        plan.tick(squad, owner, advanced)
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        assertSame(unfinished, plan.tasks[laggard.id])
        assertSame(search, unfinished.search)
        plan.tick(squad, owner, advanced.copy(now = 30))
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        assertTrue(plan.tasks.values.count { it.job == TacticalJob.ADVANCE } > movers.size)
        assertSame(unfinished, plan.tasks[laggard.id])
        assertSame(search, unfinished.search)
    }

    @Test fun `reserve rallies with distinct transit slots without demanding shooting posts`() {
        val initial = view(AttackRole.RESERVE)
        val plan = TacticalPlan(1, TacticalPattern.COORDINATED_ATTACK, focus, 0, 1)
        plan.assign(UUID(3, 3), initial)
        assertTrue(plan.tasks.values.all { it.job == TacticalJob.RESERVE && it.frontage == null })
        val points = plan.tasks.values.map { it.anchor }
        assertEquals(initial.members.size, points.distinct().size)
        assertTrue(points.all { a -> points.all { b -> a == b || a.distanceTo(b) >= 4.4 } })
    }

    @Test fun `another squads cover does not permit exposed advance under incoming fire`() {
        val initial = view()
        val pressured = initial.copy(incoming = listOf(focus), attackAssignment = initial.attackAssignment!!.copy(externalCover = true))
        assertTrue(CoordinatedAttackPositions.requiresShelter(pressured, TacticalTask(TacticalJob.ADVANCE, Vec3.ZERO, focus, 1)))
        val state = CoordinatedAttackState()
        assertEquals(40L, state.searchFailureRetry(TacticalTask(TacticalJob.ADVANCE, Vec3.ZERO, focus, 1), mapOf("occupied_or_hazard" to 4)))
        assertNull(state.searchFailureRetry(TacticalTask(TacticalJob.ADVANCE, Vec3.ZERO, focus, 1), mapOf("terrain" to 4)))
        assertNull(state.searchFailureRetry(TacticalTask(TacticalJob.COVER, Vec3.ZERO, focus, 1), mapOf("friendly_fire" to 4)))
    }
}
