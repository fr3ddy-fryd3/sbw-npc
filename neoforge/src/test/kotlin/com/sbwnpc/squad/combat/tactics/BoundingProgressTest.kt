package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** State protocol only; physical progress is measured separately by TerrainAttackGameTests. */
class BoundingProgressTest {
    private val squad = UUID(0, 100)
    private val enemy = Vec3(150.0, 64.0, 0.0)
    private fun view() =
        TacticalSnapshot(
            0,
            SquadOrder.ATTACK,
            1,
            Vec3(0.0, 64.0, 0.0),
            enemy,
            (0 until 8).map { TacticalMember(UUID(0, it.toLong()), Vec3(0.0, 64.0, (it - 3.5) * 6), canFire = true) },
            listOf(TacticalContact(UUID(1, 1), enemy, 0)),
        )

    private fun executing(): Pair<SquadTacticalState, TacticalSnapshot> {
        val state = SquadTacticalState()
        val view = view()
        state.select(TacticalChoice(TacticalPattern.BOUND, enemy), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, view)
        val deployed = view.assumingFrontArrival(plan)
        plan.tasks.values.forEach { it.position = it.staging ?: it.anchor }
        TacticalCoordinator.advance(squad, state, plan, deployed)
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        return state to deployed
    }

    private fun arrive(state: SquadTacticalState, view: TacticalSnapshot, now: Long, leave: UUID? = null): TacticalSnapshot {
        val plan = state.plan!!
        val members = view.members.map { member ->
            val task = plan.tasks[member.id]!!
            if (member.id == leave) {
                member.copy(canFire = false, recentFire = false, moving = true)
            } else {
                task.position = task.staging ?: task.anchor
                member.copy(position = task.position!!, moving = false, canFire = true, recentFire = true)
            }
        }
        return view.copy(
            now = now,
            contacts = view.contacts.map { it.copy(seenAt = now) },
            members = members,
            center = Vec3(members.map { it.position.x }.sorted()[members.size / 2], 64.0, 0.0),
        )
    }

    @Test fun `one straggler does not cancel a mostly completed covered bound`() {
        val (state, view) = executing()
        val plan = state.plan!!
        val straggler = plan.tasks.entries.first { it.value.job == TacticalJob.ADVANCE }.key
        val arrived = arrive(state, view, 170, straggler)
        TacticalCoordinator.advance(squad, state, plan, arrived)
        assertNotEquals(TacticalStatus.FAILED, plan.status)
        assertEquals(1, plan.bounds)
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        assertTrue(straggler in plan.failedMembers)
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.ADVANCE })
    }

    @Test fun `three completed bounds finish the maneuver for reassessment instead of silently stopping it`() {
        val (state, initial) = executing()
        val plan = state.plan!!
        var view = initial
        val firstMoving = plan.tasks.filterValues { it.job == TacticalJob.ADVANCE }.keys.toSet()
        repeat(3) { wave ->
            view = arrive(state, view, wave * 50L + 20)
            TacticalCoordinator.advance(squad, state, plan, view)
            if (wave < 2) {
                view = arrive(state, view, wave * 50L + 30).assumingFrontArrival(plan)
                if (wave == 0) view = view.copy(members = view.members.map { it.copy(canFire = it.id in firstMoving) })
                TacticalCoordinator.advance(squad, state, plan, view)
                if (wave == 0) {
                    assertEquals(initial.members.map { it.id }.toSet() - firstMoving, plan.tasks.filterValues { it.job == TacticalJob.ADVANCE }.keys, "Changing firing readiness must not shuffle the two moving groups")
                }
            }
        }
        assertEquals(3, plan.bounds)
        assertEquals(TacticalStatus.COMPLETED, plan.status)
        assertTrue(state.select(TacticalChoice(TacticalPattern.BOUND, enemy), 1, 150))
    }

    @Test fun `an entirely stalled moving group still fails without launching the other group`() {
        val (state, initial) = executing()
        val plan = state.plan!!
        TacticalCoordinator.advance(squad, state, plan, initial.copy(now = 170, contacts = initial.contacts.map { it.copy(seenAt = 170) }))
        assertEquals(TacticalFailure.MOVEMENT_TIMEOUT, plan.failure)
        assertEquals(0, plan.bounds)
    }

    @Test fun `incoming fire requires real supporting shots before the next bound`() {
        val state = SquadTacticalState()
        val initial = view().copy(incoming = listOf(enemy), members = view().members.map { it.copy(recentFire = false) })
        state.select(TacticalChoice(TacticalPattern.BOUND, enemy), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, initial)
        val deployed = initial.assumingFrontArrival(plan)
        plan.tasks.values.forEach { it.position = it.staging ?: it.anchor }
        TacticalCoordinator.advance(squad, state, plan, deployed)
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        val firing = deployed.copy(now = 10, members = deployed.members.map { it.copy(recentFire = true) })
        TacticalCoordinator.advance(squad, state, plan, firing)
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
    }
}
