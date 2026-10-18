package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs

class ApproachSectorsTest {
    private val objective = Point3(0.0, 64.0, 0.0)

    private fun degrees(bearings: Map<String, Double>) = bearings.mapValues { Math.round(Math.toDegrees(it.value)).toInt() }

    @Test fun `no sectors before the first contact`() {
        val sectors = ApproachSectors<String>()
        assertTrue(sectors.bearings(objective, mapOf("a" to Point3(-80.0, 64.0, 0.0), "b" to Point3(-90.0, 64.0, 10.0)), contact = false).isEmpty())
    }

    @Test fun `the nearest squad pins head on and the others flank to either side`() {
        // In-game 17.10: three squads in one front blocked each other's fire (#58).
        val sectors = ApproachSectors<String>()
        val bearings = degrees(sectors.bearings(objective, mapOf("pin" to Point3(70.0, 64.0, 0.0), "north" to Point3(90.0, 64.0, -10.0), "south" to Point3(90.0, 64.0, 10.0)), contact = true))
        assertEquals(mapOf("pin" to 0, "north" to -60, "south" to 60), bearings)
    }

    @Test fun `sectors stay put as the squads move`() {
        val sectors = ApproachSectors<String>()
        sectors.bearings(objective, mapOf("pin" to Point3(70.0, 64.0, 0.0), "flank" to Point3(90.0, 64.0, 10.0)), contact = true)
        val later = degrees(sectors.bearings(objective, mapOf("pin" to Point3(20.0, 64.0, -50.0), "flank" to Point3(30.0, 64.0, 5.0)), contact = true))
        assertEquals(mapOf("pin" to 0, "flank" to 60), later)
    }

    @Test fun `a fourth squad joins a sector and none is more than ninety degrees off head on`() {
        val sectors = ApproachSectors<String>()
        val squads = mapOf("a" to Point3(70.0, 64.0, 0.0), "b" to Point3(80.0, 64.0, 20.0), "c" to Point3(80.0, 64.0, -20.0), "d" to Point3(-80.0, 64.0, 5.0))
        val bearings = sectors.bearings(objective, squads, contact = true)
        assertEquals(4, bearings.size)
        assertTrue(bearings.values.all { abs(it - bearings.getValue("a")) <= PI / 2 + 1e-9 })
    }

    @Test fun `when the pinning squad is gone a new one takes the head on line`() {
        val sectors = ApproachSectors<String>()
        sectors.bearings(objective, mapOf("pin" to Point3(70.0, 64.0, 0.0), "flank" to Point3(90.0, 64.0, 10.0)), contact = true)
        val bearings = degrees(sectors.bearings(objective, mapOf("flank" to Point3(90.0, 64.0, 10.0), "fresh" to Point3(100.0, 64.0, 0.0)), contact = true))
        assertEquals(mapOf("flank" to 60, "fresh" to 0), bearings)
    }

    @Test fun `a named pinning group attacks head on`() {
        val sectors = ApproachSectors<String>()
        val bearings = degrees(sectors.bearings(objective, mapOf("fire" to Point3(80.0, 64.0, 0.0), "move" to Point3(70.0, 64.0, 0.0)), contact = true, pinning = "fire"))
        assertEquals(0, bearings["fire"])
        assertEquals(60, abs(bearings.getValue("move")))
    }

    @Test fun `a squad alone sends half its riflemen round a flank`() {
        val riflemen = (0L until 6L).map { UUID(0, it) }
        val grenadier = UUID(1, 0)
        val group = ApproachSectors.manoeuvreGroup(riflemen, listOf(grenadier), emptySet(), ready = 9)
        assertEquals(4, group.size)
        assertTrue(grenadier in group)
        // The same men stay in it.
        val kept = setOf(UUID(0, 5), UUID(0, 4))
        assertTrue(ApproachSectors.manoeuvreGroup(riflemen, emptyList(), kept, ready = 9).containsAll(kept))
    }

    @Test fun `a fire team too small for two groups does not split`() {
        assertTrue(ApproachSectors.manoeuvreGroup((0L until 3L).map { UUID(0, it) }, emptyList(), emptySet(), ready = 3).isEmpty())
        assertTrue(ApproachSectors.manoeuvreGroup((0L until 6L).map { UUID(0, it) }, emptyList(), emptySet(), ready = 6).isNotEmpty())
    }

    @Test fun `a flanker walks round the objective in fixed steps outside the line's radius`() {
        val sector = Math.toRadians(-60.0)
        // Standing head-on, 80 blocks out: the first waypoint is 30 degrees short of the sector.
        val first = ApproachSectors.arcWaypoint(objective, Point3(80.0, 64.0, 0.0), sector, 100.0)!!
        assertEquals(-30.0, Math.toDegrees(kotlin.math.atan2(first.z, first.x)), 1e-6)
        assertEquals(100.0 + ApproachSectors.ARC_MARGIN, kotlin.math.hypot(first.x, first.z), 1e-6)
        // The waypoint stays put while he walks towards it.
        val halfway = ApproachSectors.arcWaypoint(objective, Point3(105.0, 64.0, -28.0), sector, 100.0)!!
        assertEquals(first.x, halfway.x, 1e-6)
        assertEquals(first.z, halfway.z, 1e-6)
        // Near it, he makes for his post.
        assertEquals(null, ApproachSectors.arcWaypoint(objective, Point3(86.0, 64.0, -50.0), sector, 100.0))
    }

    @Test fun `flanks go to the side each squad already stands on`() {
        // Headless battle on the owner's terrain, 17.10: the second squad, standing next to the
        // pinning one, took the side the third stood on and the third walked round the point.
        val sectors = ApproachSectors<String>()
        val squads = mapOf("pin" to Point3(70.0, 64.0, 0.0), "beside" to Point3(72.0, 64.0, 4.0), "off" to Point3(10.0, 64.0, 75.0))
        val bearings = degrees(sectors.bearings(objective, squads, contact = true))
        assertEquals(60, bearings["off"])
        assertEquals(-60, bearings["beside"])
    }
}
