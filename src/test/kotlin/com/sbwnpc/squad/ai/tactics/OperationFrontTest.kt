package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class OperationFrontTest {
    private val actors = (0 until 64).map { FormationActor(UUID(0, it.toLong()), UUID(1, (it / 32).toLong()), Point3(0.0, 1.0, it % 32 * 2.0)) }
    private val focus = Point3(120.0, 1.0, 32.0)

    @Test fun `compact enemy leaves rear capacity and both squads contribute movers and support`() {
        val proposals = OperationFront.propose(actors, listOf(focus), focus)
        assertEquals(64, proposals.size)
        assertTrue(proposals.values.count { it.duty == CombatDuty.REAR_GUARD } >= 8)
        actors.groupBy { it.squad }.values.forEach { squad ->
            assertTrue(squad.any { proposals.getValue(it.id).duty == CombatDuty.MOVE })
            assertTrue(squad.any { proposals.getValue(it.id).duty == CombatDuty.FIRE })
        }
        proposals.values.filter { it.duty == CombatDuty.REAR_GUARD }.forEach { assertTrue(it.watch!!.x < it.anchor.x) }
    }

    @Test fun `wider reported enemy creates wider front and keeps support capacity`() {
        fun width(map: Map<UUID, FrontProposal>): Double {
            val points = map.values.filter { it.duty == CombatDuty.MOVE }.map { it.anchor.z }
            return points.max() - points.min()
        }
        val compact = OperationFront.propose(actors, listOf(focus), focus)
        val wide = OperationFront.propose(actors, listOf(Point3(120.0, 1.0, -64.0), Point3(120.0, 1.0, 128.0)), focus)
        assertTrue(width(wide) > width(compact) * 2)
        assertTrue(wide.values.any { it.duty == CombatDuty.FIRE })
        assertEquals(wide, OperationFront.propose(actors.reversed(), listOf(Point3(120.0, 1.0, -64.0), Point3(120.0, 1.0, 128.0)), focus))
    }

    @Test fun `small force never allocates everyone to movement waiting for nonexistent support`() {
        for (size in 1..8) {
            val result = OperationFront.propose(actors.take(size), listOf(focus), focus)
            assertTrue(result.values.any { it.duty == CombatDuty.FIRE })
        }
    }

    @Test fun `short range capability is allocated toward reachable firing distance`() {
        val actor = actors.first().copy(engagementDistance = 32.0)
        val result = OperationFront.propose(listOf(actor), listOf(focus), focus).getValue(actor.id)
        assertTrue(result.anchor.horizontalDistanceSquared(focus) < 48.0 * 48.0)
    }

    @Test fun `reinforcement fills extra capacity without redistributing surviving roles`() {
        val before = OperationFront.propose(actors, listOf(focus), focus)
        val reinforcement = (64L..67L).map { FormationActor(UUID(0, it), UUID(1, 3), Point3(0.0, 1.0, 32.0)) }
        val after = OperationFront.propose(actors + reinforcement, listOf(focus), focus, before.mapValues { it.value.duty })
        actors.forEach { assertEquals(before.getValue(it.id).duty, after.getValue(it.id).duty) }
        assertTrue(reinforcement.any { after.getValue(it.id).duty == CombatDuty.REAR_GUARD })
    }
}
