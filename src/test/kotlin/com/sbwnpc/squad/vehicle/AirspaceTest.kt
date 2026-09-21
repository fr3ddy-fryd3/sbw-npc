package com.sbwnpc.squad.vehicle

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AirspaceTest {

    private fun uuid(n: Int) = UUID(0L, n.toLong())

    @Test
    fun `an empty sky leaves the station exactly where it was`() {
        val station = Vec3(10.0, 70.0, 10.0)
        assertSame(station, Airspace.separate(Vec3.ZERO, station, emptyList()))
    }

    @Test
    fun `a neighbour further out than the separation is ignored`() {
        val station = Vec3(10.0, 70.0, 10.0)
        val far = Vec3(0.0, 70.0, Airspace.SEPARATION + 5.0)
        assertSame(station, Airspace.separate(Vec3.ZERO, station, listOf(far)))
    }

    @Test
    fun `the station is pushed directly away from a neighbour that is too close`() {
        val self = Vec3(0.0, 70.0, 0.0)
        val other = Vec3(0.0, 70.0, 6.0)
        val moved = Airspace.separate(self, Vec3(0.0, 70.0, 0.0), listOf(other))
        // Straight along -Z, by whatever the gap is short of the separation.
        assertEquals(0.0, moved.x, 1.0e-9)
        assertEquals(-(Airspace.SEPARATION - 6.0), moved.z, 1.0e-9)
    }

    @Test
    fun `two aircraft holding one station end up pointed away from each other`() {
        val a = Vec3(0.0, 70.0, 0.0)
        val b = Vec3(0.0, 70.0, 4.0)
        val station = Vec3(0.0, 70.0, 2.0)
        val forA = Airspace.separate(a, station, listOf(b))
        val forB = Airspace.separate(b, station, listOf(a))
        assertTrue(forA.z < station.z, "the one to the south is sent further south")
        assertTrue(forB.z > station.z, "the one to the north is sent further north")
    }

    @Test
    fun `co-located aircraft still get a push rather than a divide by zero`() {
        val here = Vec3(5.0, 70.0, 5.0)
        val moved = Airspace.separate(here, here, listOf(here))
        assertTrue(moved.distanceTo(here) > 0.0)
    }

    @Test
    fun `altitude bands are assigned by a stable order, not by arrival`() {
        val first = uuid(1)
        val second = uuid(2)
        val third = uuid(3)
        assertEquals(0, Airspace.layerFor(first, listOf(second, third)))
        assertEquals(1, Airspace.layerFor(second, listOf(first, third)))
        assertEquals(2, Airspace.layerFor(third, listOf(first, second)))
        // Same answer whichever order the neighbours happen to come back in.
        assertEquals(1, Airspace.layerFor(second, listOf(third, first)))
    }

    @Test
    fun `stacking stops at the last band`() {
        val many = (1..10).map { uuid(it) }
        val highest = uuid(11)
        assertEquals(Airspace.MAX_LAYERS - 1, Airspace.layerFor(highest, many))
        assertEquals((Airspace.MAX_LAYERS - 1) * Airspace.LAYER_HEIGHT, Airspace.clearanceFor(highest, many))
    }
}
