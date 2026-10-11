package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.tactics.operation.*
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AttackOperationTest {
    private val focus = Vec3(120.0, 64.0, 0.0)
    private fun force(id: Int, count: Int, z: Double = 0.0, stamp: Int = 1) =
        AttackForce(
            UUID(0, id.toLong()),
            stamp,
            SquadFaction.PIG,
            focus,
            Vec3(0.0, 64.0, z),
            (0 until count).map { TacticalMember(UUID(id.toLong(), it.toLong()), Vec3(0.0, 64.0, z + (it - (count - 1) / 2.0) * 6.0)) },
        )

    @Test fun `largest primary armed force owns center regardless of insertion order or position`() {
        val forces = listOf(force(1, 7, -12.0), force(2, 7), force(3, 16, 12.0))
        for (ordered in listOf(forces, forces.reversed(), listOf(forces[1], forces[2], forces[0]))) {
            val operation = AttackOperation(1, focus, ordered, 0)
            assertEquals(AttackRole.CENTER, operation.lanes.getValue(forces[2].id).role)
            assertEquals(AttackRole.LEFT, operation.lanes.getValue(forces[0].id).role)
            assertEquals(AttackRole.RIGHT, operation.lanes.getValue(forces[1].id).role)
            assertNotEquals(operation.lanes.getValue(forces[0].id).waypoint, operation.lanes.getValue(forces[1].id).waypoint)
        }
    }

    @Test fun `equal forces choose a spatially central squad and rally the reserve behind it`() {
        val forces = listOf(force(1, 32, 48.0), force(2, 32, -24.0), force(3, 32), force(4, 32, 24.0))
        for (ordered in listOf(forces, forces.reversed())) {
            val operation = AttackOperation(1, focus, ordered, 0)
            assertEquals(AttackRole.CENTER, operation.lanes.getValue(forces[2].id).role)
            val reserve = operation.lanes.values.single { it.role == AttackRole.RESERVE }
            assertEquals(-32.0, reserve.sector.x, 0.01)
            assertTrue(kotlin.math.abs(reserve.sector.z) <= 16.0)
        }
    }

    @Test fun `casualties preserve surviving roles and soldier slots while a fresh order revokes participation`() {
        val forces = listOf(force(1, 16), force(2, 7, -10.0), force(3, 7, 10.0))
        val operation = AttackOperation(1, focus, forces, 0)
        val original = operation.lanes.getValue(forces[0].id)
        val slots = original.slots.toMap()
        val wounded = forces[0].copy(members = forces[0].members.takeLast(5))
        operation.tick(listOf(wounded) + forces.drop(1), 10)
        assertSame(original, operation.lanes.getValue(forces[0].id))
        assertEquals(AttackRole.CENTER, original.role)
        wounded.members.forEach { assertEquals(slots[it.id], operation.assignment(wounded.id, listOf(wounded) + forces.drop(1))!!.slots[it.id]) }
        operation.tick(listOf(wounded.copy(stamp = 2)) + forces.drop(1), 20)
        assertNull(operation.assignment(wounded.id, forces))
        assertEquals(2, operation.lanes.size)
    }

    @Test fun `pistol only roster does not displace a smaller usable center and fourth squad reserves`() {
        val unusable = force(1, 32).let { it.copy(members = it.members.map { member -> member.copy(primaryAmmo = false, sidearmOnly = true) }) }
        val central = force(2, 16)
        val forces = listOf(unusable, central, force(3, 7, -10.0), force(4, 7, 10.0))
        val operation = AttackOperation(1, focus, forces, 0)
        assertEquals(AttackRole.CENTER, operation.lanes.getValue(central.id).role)
        assertEquals(AttackRole.RESERVE, operation.lanes.getValue(unusable.id).role)
    }

    @Test fun `blind blocked wing times out explicitly without recalling the other wing or claiming successful deployment`() {
        val forces = listOf(force(1, 16), force(2, 7, -10.0), force(3, 7, 10.0))
        val events = mutableListOf<AttackOperationEvent>()
        val operation = AttackOperation(1, focus, forces, 0, events = AttackOperationEvents(events::add))
        val other = operation.lanes.getValue(forces[2].id)
        val otherWaypoint = other.waypoint

        // Pure lifecycle fixture: relocate the whole force, not merely its median metadata.
        fun at(point: Vec3): List<AttackForce> =
            forces.map { force ->
                if (force.id != other.squad) {
                    force
                } else {
                    val facing = focus.subtract(other.sector).multiply(1.0, 0.0, 1.0).normalize()
                    val side = Vec3(-facing.z, 0.0, facing.x)
                    force.copy(center = point, members = force.members.map { it.copy(position = point.add(side.scale(other.slots[it.id]!!))) })
                }
            }
        operation.tick(at(otherWaypoint), 10)
        operation.tick(at(other.sector), 100)
        operation.tick(at(other.sector), 410)
        val blocked = operation.assignment(forces[1].id, forces)!!
        assertTrue(blocked.blocked)
        assertTrue(blocked.holding)
        assertTrue(other.deployed)
        assertFalse(other.blocked)
        assertTrue(events.any { it.action == "route_blocked" && it.squad == forces[1].id })
        assertFalse(events.any { it.action == "sector_reached" && it.squad == forces[1].id })
    }

    @Test fun `cover reports from the same squad cannot authorize inter squad support`() {
        val forces = listOf(force(1, 16), force(2, 7), force(3, 7))
        val operation = AttackOperation(1, focus, forces, 0)
        val reports = forces.mapIndexed { index, force -> force.copy(covering = if (index == 0) 2 else 0) }
        assertFalse(operation.assignment(forces[0].id, reports)!!.externalCover)
        assertTrue(operation.assignment(forces[1].id, reports)!!.externalCover)
        assertFalse(operation.assignment(forces[1].id, reports.map { it.copy(covering = 0) })!!.externalCover)
    }

    @Test fun `corridor accepts joining stragglers but rejects paths through another strip`() {
        val corridor = AttackCorridor(listOf(Vec3(0.0, 64.0, 0.0), Vec3(40.0, 64.0, 0.0)), 8.0)
        val straggler = Vec3(0.0, 64.0, 24.0)
        assertTrue(corridor.permits(straggler, Vec3(6.0, 64.0, 20.0)))
        assertFalse(corridor.permits(straggler, Vec3(6.0, 64.0, 26.0)))
        assertFalse(corridor.permits(Vec3.ZERO, Vec3(30.0, 64.0, 24.0)))
        assertTrue(corridor.permits(Vec3.ZERO, Vec3(30.0, 64.0, 8.0)))
    }

    @Test fun `near contact does not recall center to an arbitrary engagement distance`() {
        val forces = listOf(force(1, 16), force(2, 7))
        val operation = AttackOperation(1, Vec3(10.0, 64.0, 0.0), forces, 0)
        assertEquals(forces.first().center, operation.lanes.getValue(forces.first().id).sector)
    }

    @Test fun `late third squad fills vacant wing without replacing ongoing roles or slots`() {
        val forces = listOf(force(1, 16), force(2, 7, -10.0))
        val operation = AttackOperation(1, focus, forces, 0)
        val center = operation.lanes.getValue(forces[0].id)
        val left = operation.lanes.getValue(forces[1].id)
        val reinforcement = force(3, 7, 10.0)
        operation.join(reinforcement, forces + reinforcement, 200)
        assertSame(center, operation.lanes.getValue(forces[0].id))
        assertSame(left, operation.lanes.getValue(forces[1].id))
        assertEquals(AttackRole.RIGHT, operation.lanes.getValue(reinforcement.id).role)
        assertTrue(operation.lanes.getValue(reinforcement.id).waypoint.z > 0.0)
    }

    @Test fun `moving a median alone cannot claim a fully deployed squad`() {
        val forces = listOf(force(1, 16), force(2, 7, -10.0), force(3, 7, 10.0))
        val operation = AttackOperation(1, focus, forces, 0)
        val wing = operation.lanes.getValue(forces[1].id)
        operation.tick(forces.map { if (it.id == wing.squad) it.copy(center = wing.waypoint) else it }, 10)
        assertEquals(1, wing.next)
        assertFalse(wing.deployed)
    }

    @Test fun `new barracks recruit takes a casualty vacancy without growing or renumbering the line`() {
        val forces = listOf(force(1, 16), force(2, 7))
        val operation = AttackOperation(1, focus, forces, 0)
        val lane = operation.lanes.getValue(forces[0].id)
        val original = lane.slots.toMap()
        val replacement = forces[0].members[4].copy(id = UUID(99, 99))
        val replenished = forces[0].copy(members = forces[0].members.toMutableList().also { it[4] = replacement })
        operation.tick(listOf(replenished, forces[1]), 10)
        assertEquals(original[forces[0].members[4].id], lane.slots[replacement.id])
        replenished.members.filter { it.id != replacement.id }.forEach { assertEquals(original[it.id], lane.slots[it.id]) }
        assertEquals(16, lane.slots.size)
    }
}
