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

    @Test fun `validated recovery changes only the blocked lane and does not fabricate arrival`() {
        val forces = listOf(force(1, 16), force(2, 7, -10.0), force(3, 7, 10.0))
        val operation = AttackOperation(1, focus, forces, 0)
        operation.tick(forces, 0)
        operation.tick(forces, 410)
        val lane = operation.lanes.getValue(forces[1].id)
        val others = operation.lanes.filterKeys { it != lane.squad }.mapValues { it.value.route.toList() to it.value.revision }
        val target = lane.retry.target!!
        val origin = forces[1].center
        val checkpoint = origin.add(16.0, 0.0, -16.0)
        operation.recover(lane, origin, checkpoint, listOf(origin, checkpoint, target), 610)
        assertFalse(lane.blocked)
        assertFalse(lane.deployed)
        assertEquals(target, lane.sector)
        assertEquals(checkpoint, lane.waypoint)
        assertEquals(AttackRole.LEFT, lane.role)
        others.forEach { (id, old) -> assertEquals(old, operation.lanes.getValue(id).route.toList() to operation.lanes.getValue(id).revision) }
    }

    @Test fun `advancing a recovered lane preserves its native detour for late and blind shooters`() {
        val forces = listOf(force(1, 16), force(2, 7, -10.0))
        val operation = AttackOperation(1, focus, forces, 0)
        val lane = operation.lanes.getValue(forces[0].id)
        val post = Vec3(52.0, 64.0, 0.0)
        val bend = Vec3(16.0, 64.0, -32.0)
        lane.route += post
        lane.next = lane.route.size
        lane.corridor = AttackCorridor(listOf(forces[0].center, bend, post), 8.0)
        val old = lane.corridor
        operation.lanes.getValue(forces[1].id).blocked = true
        val blind = forces.map { it.copy(blockedShooters = it.fighters.size) }
        operation.transition(EngageAttack(), 0, "fixture_deployed")
        operation.tick(blind, 40)
        operation.tick(blind, 50)
        assertNotEquals(post, lane.waypoint)
        assertEquals(old.points + lane.waypoint, lane.corridor.points)
        assertTrue(lane.corridor.permits(Vec3.ZERO, bend))
        assertFalse(lane.corridor.permits(Vec3.ZERO, Vec3(16.0, 64.0, 100.0)))
    }

    @Test fun `a quiet majority of blocked shooters requests a short approach even inside nominal range`() {
        for (unlocated in listOf(false, true)) {
            val forces = listOf(force(1, 16), force(2, 7, -10.0))
            val events = mutableListOf<AttackOperationEvent>()
            val operation = AttackOperation(1, focus, forces, 0, events = AttackOperationEvents(events::add))
            val center = operation.lanes.getValue(forces[0].id)
            val post = Vec3(52.0, 64.0, 0.0)
            center.route += post
            center.next = center.route.size
            val wing = operation.lanes.getValue(forces[1].id)
            wing.blocked = true
            val blind = forces.map { it.copy(blockedShooters = if (unlocated) 0 else it.fighters.size, unlocatedShooters = if (unlocated) it.fighters.size else 0) }
            operation.transition(EngageAttack(), 0, "fixture_deployed")
            operation.tick(blind, 40)
            assertEquals(AttackStage.ADVANCING, operation.state.stage)
            operation.tick(blind, 50)
            assertEquals(8.0, center.waypoint.subtract(post).horizontalDistance(), 0.001)
            assertFalse(center.deployed)
            assertTrue(events.any { it.action == "advance_ordered" && it.reason == if (unlocated) "quiet_sector_seeking_contact" else "quiet_sector_blocked_shots" })

            val next = center.waypoint
            operation.tick(blind, 60)
            assertEquals(next, center.waypoint, "A pending bound must not append another goal each update")
        }
    }

    @Test fun `a blind sector under fire needs real adjacent fire and respects suppression`() {
        for (unlocated in listOf(false, true)) {
            for (suppressed in listOf(false, true)) {
                val forces = listOf(force(1, 16), force(2, 7, -10.0))
                val operation = AttackOperation(1, focus, forces, 0)
                val lane = operation.lanes.getValue(forces[0].id)
                lane.next = lane.route.size
                operation.lanes.getValue(forces[1].id).blocked = true
                operation.transition(AdvanceAttack(), 0, "fixture")
                val pressured = forces.map { force ->
                    force.copy(blockedShooters = if (unlocated) 0 else force.fighters.size, unlocatedShooters = if (unlocated) force.fighters.size else 0, underFire = true, members = force.members.map { it.copy(suppressed = suppressed) })
                }
                val previous = lane.waypoint
                operation.tick(pressured, 10)
                assertEquals(previous, lane.waypoint)
                val covered = pressured.mapIndexed { index, force -> force.copy(covering = if (index == 1) 2 else 0) }
                operation.tick(covered, 70)
                assertEquals(!suppressed, !lane.deployed)
            }
        }
    }

    @Test fun `medics and a guarding soldier cannot hide a physically deployed firing line`() {
        val wing = force(2, 32, -24.0).let { force ->
            force.copy(
                members = force.members.mapIndexed { index, member ->
                    if (index >= 28) member.copy(role = com.sbwnpc.squad.npc.NpcClass.MEDIC) else member
                },
            )
        }
        val forces = listOf(force(1, 32), wing, force(3, 7, 24.0))
        val operation = AttackOperation(1, focus, forces, 0)
        val lane = operation.lanes.getValue(wing.id)
        val waypoint = lane.waypoint
        val facing = focus.subtract(lane.sector).multiply(1.0, 0.0, 1.0).normalize()
        val side = Vec3(-facing.z, 0.0, facing.x)
        val guard = lane.slots.maxBy { it.value }.key
        val members = wing.members.map { member ->
            val offset = if (member.id == guard || member.role == com.sbwnpc.squad.npc.NpcClass.MEDIC) -60.0 else lane.slots.getValue(member.id)
            member.copy(position = waypoint.add(side.scale(offset)))
        }
        val median = Vec3(members.map { it.position.x }.sorted()[16], waypoint.y, members.map { it.position.z }.sorted()[16])
        assertTrue(median.subtract(waypoint).horizontalDistance() > 10.0, "Fixture must reproduce the shifted squad median")
        val deployed = wing.copy(center = median, members = members)
        operation.tick(listOf(forces[0], deployed, forces[2]), 10)
        assertEquals(2, lane.next, "Reached firing slots must advance the route despite rear medical roles")
        assertFalse(lane.blocked)
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

    @Test fun `late full reserve replaces a deployed center with only two local fighters despite a distant full roster`() {
        val initial = listOf(force(1, 16), force(2, 16, -20.0), force(3, 16, 20.0))
        val events = mutableListOf<AttackOperationEvent>()
        val operation = AttackOperation(1, focus, initial, 0, events = AttackOperationEvents(events::add))
        fun placed(forces: List<AttackForce>): List<AttackForce> =
            forces.map { force ->
                val lane = operation.lanes.getValue(force.id)
                val facing = focus.subtract(lane.sector).multiply(1.0, 0.0, 1.0).normalize()
                val side = Vec3(-facing.z, 0.0, facing.x)
                force.copy(center = lane.waypoint, members = force.members.map { it.copy(position = lane.waypoint.add(side.scale(lane.slots[it.id] ?: 0.0))) })
            }
        operation.tick(placed(initial), 10)
        val deployed = placed(initial)
        operation.tick(deployed, 20)
        val reserve = force(4, 32).let { f -> f.copy(center = Vec3(-24.0, 64.0, 0.0), members = f.members.mapIndexed { index, m -> m.copy(position = Vec3(-24.0 + index % 8 * 2, 64.0, index / 8 * 2.0)) }) }
        operation.join(reserve, deployed + reserve, 200)
        val oldCenter = operation.lanes.getValue(initial[0].id)
        val snapshot = deployed.map { f ->
            if (f.id != oldCenter.squad) f else f.copy(center = Vec3(-180.0, 64.0, 0.0), members = f.members.mapIndexed { index, member -> if (index < 2) member else member.copy(position = Vec3(-180.0, 64.0, index.toDouble())) })
        } + reserve
        operation.tick(snapshot, 210)
        assertEquals(AttackRole.RESERVE, operation.lanes.getValue(reserve.id).role, "One brief gap must not reshuffle active sectors")
        operation.tick(snapshot, 420)
        assertEquals(AttackRole.CENTER, operation.lanes.getValue(reserve.id).role, "Two nearby fighters and distant recruits must not hold a full arrived reserve indefinitely")
        assertEquals(AttackRole.RESERVE, operation.lanes.getValue(oldCenter.squad).role)
        assertTrue(operation.lanes.getValue(reserve.id).revision > 0, "The local state must see the changed role")
        assertTrue(events.any { it.action == "reserve_activated" && it.squad == reserve.id })
    }
}
