package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalClearanceTest {
    private val origin = Vec3(80.5, 64.0, 0.5)
    private val enemy = Vec3(0.5, 64.0, 0.5)
    private fun view(now: Long, contacts: List<TacticalContact>) = TacticalSnapshot(now, SquadOrder.ATTACK, 1, origin, enemy, emptyList(), contacts)

    @Test fun `another known enemy outside the focus sector also blocks a close firing post`() {
        val point = origin.add(0.0, 0.0, 12.0)
        val front = TacticalContact(UUID(0, 1), enemy, 100)
        val side = TacticalContact(UUID(0, 2), point.add(0.0, 0.0, 24.0), 50)
        assertTrue(TacticalClearance.permitsPosition(NpcClass.RIFLEMAN, origin, point, view(100, listOf(front))))
        assertFalse(TacticalClearance.permitsPosition(NpcClass.RIFLEMAN, origin, point, view(100, listOf(front, side))))
        assertTrue(TacticalClearance.permitsPosition(NpcClass.RIFLEMAN, origin, point, view(251, listOf(front, side))))
    }

    @Test fun `path safety checks the remaining route rather than nodes already traversed`() {
        val path = Path(listOf(Node(0, 64, 0), Node(80, 64, 0), Node(80, 64, 12)), BlockPos(80, 64, 12), true)
        val view = view(10, listOf(TacticalContact(UUID(0, 1), enemy, 0)))
        assertFalse(TacticalClearance.permitsPath(NpcClass.RIFLEMAN, origin, path, view))
        path.nextNodeIndex = 1
        assertTrue(TacticalClearance.permitsPath(NpcClass.RIFLEMAN, origin, path, view))
    }

    @Test fun `a new contact invalidates an otherwise safe running route`() {
        val path = Path(listOf(Node(80, 64, 0), Node(80, 64, 12)), BlockPos(80, 64, 12), true)
        val front = TacticalContact(UUID(0, 1), enemy, 0)
        val side = TacticalContact(UUID(0, 2), Vec3(80.5, 64.0, 36.5), 10)
        assertTrue(TacticalClearance.permitsPath(NpcClass.RIFLEMAN, origin, path, view(10, listOf(front))))
        assertFalse(TacticalClearance.permitsPath(NpcClass.RIFLEMAN, origin, path, view(10, listOf(front, side))))
    }
}
