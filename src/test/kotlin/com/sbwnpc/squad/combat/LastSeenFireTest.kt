package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LastSeenFireTest {
    private val target = UUID.randomUUID()
    private val oldEye = Vec3(120.0, 65.0, 3.0)

    @Test
    fun `concealed contacts cannot grant suppression without a personal sighting`() {
        val fire = LastSeenFire()
        assertFalse(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 0))
        fire.observe(target, oldEye, 0)
        assertFalse(fire.hidden(UUID.randomUUID(), DetectionSightline.Visibility.FOLIAGE, 1))
        assertFalse(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 200))
        assertNull(fire.point(200))
    }

    @Test
    fun `continuous concealment spends exactly two paced bursts at the frozen last seen point`() {
        val fire = LastSeenFire()
        fire.observe(target, oldEye, 0)
        assertTrue(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 1))
        val shots = mutableListOf<Long>()
        for (now in 1L..250L) {
            assertFalse(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, now))
            if (fire.ready(now)) {
                assertEquals(oldEye, fire.point(now))
                shots += now
                fire.shot(now, 2)
            }
        }
        assertEquals(listOf(1L, 3L, 5L, 15L, 17L, 19L), shots)
        assertEquals(0, fire.roundsLeft)
        assertNull(fire.point(20))
    }

    @Test
    fun `visible reacquisition cancels blind fire and flicker cannot refill it`() {
        val fire = LastSeenFire()
        fire.observe(target, oldEye, 0)
        fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 1)
        fire.shot(1, 2)
        val newEye = Vec3(125.0, 66.0, 9.0)
        fire.observe(target, newEye, 2)
        assertNull(fire.point(2))
        assertFalse(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 3))
        fire.observe(target, newEye, 161)
        assertTrue(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 162))
        assertEquals(newEye, fire.point(162))
    }

    @Test
    fun `solid cover cancels an episode and held fire expires rather than resuming much later`() {
        val fire = LastSeenFire()
        fire.observe(target, oldEye, 0)
        fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 1)
        fire.hidden(target, DetectionSightline.Visibility.OBSTACLE, 2)
        assertNull(fire.point(2))
        fire.observe(target, oldEye, 200)
        fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 201)
        assertNotNull(fire.point(300))
        assertNull(fire.point(301))
        assertFalse(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 400))
    }

    @Test
    fun `forgetting another life preserves fire while forgetting the observed life removes its aim and bursts`() {
        val fire = LastSeenFire()
        fire.observe(target, oldEye, 0)
        fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 1)
        fire.forget(UUID.randomUUID())
        assertEquals(oldEye, fire.point(2))
        assertEquals(6, fire.roundsLeft)
        fire.forget(target)
        assertFalse(fire.observed(target))
        assertNull(fire.point(2))
        assertEquals(0, fire.roundsLeft)
        assertFalse(fire.hidden(target, DetectionSightline.Visibility.FOLIAGE, 3))
    }
}
