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

    @Test fun `new operation bound after a long hold receives its own movement deadline`() {
        val base = view()
        val held = base.copy(attackAssignment = base.attackAssignment!!.copy(deployed = true))
        val squad = UUID(3, 3)
        val owner = SquadTacticalState()
        val plan = TacticalPlan(1, TacticalPattern.COORDINATED_ATTACK, focus, 0, 1, TacticalStatus.EXECUTING)
        plan.tick(squad, owner, held)
        plan.tick(squad, owner, held.copy(now = 1000))
        val next = base.copy(now = 1010, attackAssignment = base.attackAssignment!!.copy(revision = 1))
        plan.tick(squad, owner, next)
        assertEquals(TacticalStatus.EXECUTING, plan.status)
        assertEquals(1010, plan.phaseSince)
        assertEquals(8, plan.tasks.values.count { it.job == TacticalJob.ADVANCE })
        plan.tick(squad, owner, next.copy(now = 1200))
        assertEquals(TacticalStatus.EXECUTING, plan.status, "Repeating the same revision must keep the current bound")
        plan.tick(squad, owner, next.copy(now = 1331))
        assertEquals(TacticalFailure.MOVEMENT_TIMEOUT, plan.failure, "A truly immobile bound must still expire")
    }

    @Test fun `a failed member rejoins a new operation route but not every holding tick`() {
        val base = view()
        val owner = SquadTacticalState()
        val squad = UUID(3, 3)
        val plan = TacticalPlan(1, TacticalPattern.COORDINATED_ATTACK, focus, 0, 1, TacticalStatus.EXECUTING)
        val held = base.copy(attackAssignment = base.attackAssignment!!.copy(deployed = true, blocked = true))
        plan.tick(squad, owner, held)
        val member = base.members.first().id
        plan.assignments.abandon(member, 10, "failed old route")
        plan.tick(squad, owner, held.copy(now = 20))
        assertTrue(member in plan.failedMembers)
        assertNull(plan.tasks[member])
        val recovered = base.copy(now = 200, attackAssignment = base.attackAssignment!!.copy(revision = 1))
        plan.tick(squad, owner, recovered)
        assertFalse(member in plan.failedMembers)
        assertEquals(TacticalJob.ADVANCE, plan.tasks[member]?.job)
        plan.assignments.abandon(member, 210, "still unavailable")
        plan.tick(squad, owner, recovered.copy(now = 220))
        assertNull(plan.tasks[member], "An unchanged revision retried its failed actor repeatedly")
    }

    @Test fun `a new sector never recalls shooters that already recovered farther forward`() {
        val base = view()
        val advanced = base.copy(members = base.members.map { it.copy(position = it.position.add(68.0, 0.0, 0.0), canFire = true, firingAt = focus) })
        for (deployed in listOf(false, true)) {
            val next = advanced.copy(attackAssignment = base.attackAssignment!!.copy(revision = 1, deployed = deployed, waypoint = base.attackAssignment!!.sector))
            val plan = TacticalPlan(1, TacticalPattern.COORDINATED_ATTACK, focus, 0, 1, TacticalStatus.EXECUTING)
            plan.tick(UUID(3, 3), SquadTacticalState(), next)
            next.members.forEach { member ->
                val task = plan.tasks.getValue(member.id)
                assertTrue(task.anchor.x >= member.position.x, "Revision pulled a leading shooter back: ${task.anchor}")
                assertNotEquals(TacticalJob.ADVANCE, task.job, "A reached forward post is already covering")
            }
        }
    }

    @Test fun `a wing opens its lateral route before applying the final firing line depth`() {
        val base = view(AttackRole.LEFT)
        val initial = base.copy(members = base.members.map { it.copy(canFire = true, firingAt = focus) }, attackAssignment = base.attackAssignment!!.copy(waypoint = Vec3(0.0, 64.0, 72.0), facing = Vec3(1.0, 0.0, -1.0).normalize()))
        val plan = TacticalPlan(1, TacticalPattern.COORDINATED_ATTACK, focus, 0, 1, TacticalStatus.EXECUTING)
        plan.tick(UUID(3, 3), SquadTacticalState(), initial)
        initial.members.forEach { member ->
            val leg = plan.tasks.getValue(member.id).anchor.subtract(member.position)
            assertTrue(leg.z >= 4.0 * kotlin.math.abs(leg.x), "A wing skipped its lateral detour toward the final sector: $leg")
        }
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

    @Test fun `crossing a ridge keeps one operation plan through visible and remembered height changes`() {
        val base = view()
        val owner = SquadTacticalState()
        val squad = UUID(3, 3)
        var serial: Int? = null
        for ((index, height) in listOf(64.0, 72.0, 56.0, 64.0, 72.0).withIndex()) {
            for (visible in listOf(true, false)) {
                val now = index * 100L + if (visible) 0 else 50
                val next = base.copy(
                    now = now,
                    center = base.center.add(0.0, height - 64.0, 0.0),
                    members = base.members.map { it.copy(position = it.position.add(0.0, height - 64.0, 0.0)) },
                    contacts = base.contacts.map { it.copy(seenAt = now - if (visible) 0 else 60) },
                    attackAssignment = base.attackAssignment!!.copy(deployed = true),
                )
                owner.assess(squad, next)
                assertEquals(TacticalPattern.COORDINATED_ATTACK, owner.plan!!.pattern, "Height interrupted operation at $height; visible=$visible")
                if (serial == null) serial = owner.plan!!.id
                assertEquals(serial, owner.plan!!.id, "Normal height changes cancelled the operation's work")
            }
        }
        owner.assess(squad, base.copy(now = 500, grenade = true))
        assertEquals(TacticalPattern.EVADE, owner.plan!!.pattern, "Operation ownership must not suppress emergencies")
    }
}
