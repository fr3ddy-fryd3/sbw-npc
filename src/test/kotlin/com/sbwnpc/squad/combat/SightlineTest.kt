package com.sbwnpc.squad.combat

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SightlineTest {
    /** A vehicle-sized box sitting between z=4 and z=8, a couple of blocks tall. */
    private val hull = AABB(-1.5, 0.0, 4.0, 1.5, 2.4, 8.0)
    private val hulls = listOf(hull)

    @Test
    fun `a shot straight through the hull is blocked`() {
        assertTrue(Sightline.crosses(hulls, Vec3(0.0, 1.6, 0.0), Vec3(0.0, 1.6, 20.0)))
    }

    @Test
    fun `a shot well clear of the hull is not blocked`() {
        assertFalse(Sightline.crosses(hulls, Vec3(12.0, 1.6, 0.0), Vec3(12.0, 1.6, 20.0)))
    }

    @Test
    fun `a shot over the top of the hull is not blocked`() {
        assertTrue(Sightline.crosses(hulls, Vec3(0.0, 1.6, 0.0), Vec3(0.0, 1.6, 20.0)))
        assertFalse(Sightline.crosses(hulls, Vec3(0.0, 6.0, 0.0), Vec3(0.0, 6.0, 20.0)))
    }

    /**
     * The rule that took several goes to get right: a hull blocks a shot only when it is AHEAD on
     * the line, not merely because it surrounds the muzzle.
     *
     * A vehicle's box is a box drawn around a shape that is not one, so a soldier standing against
     * the side of an APC is frequently inside that box. Treating "inside" as blocked stops it
     * firing in every direction at once, including straight away from the vehicle — which in play
     * looked like a whole squad standing around a BMP refusing to shoot anything at all.
     */
    @Test
    fun `standing inside the hull box and firing away from it is allowed`() {
        val insideHull = Vec3(0.0, 1.6, 6.0)
        assertFalse(Sightline.crosses(hulls, insideHull, Vec3(0.0, 1.6, -30.0)))
    }

    @Test
    fun `a hull behind the shooter never blocks`() {
        // The hull spans z 4..8; firing from z=10 out to z=30 leaves it astern.
        assertFalse(Sightline.crosses(hulls, Vec3(0.0, 1.6, 10.0), Vec3(0.0, 1.6, 30.0)))
    }

    @Test
    fun `a hull just ahead of the shooter blocks even at point blank`() {
        assertTrue(Sightline.crosses(hulls, Vec3(0.0, 1.6, 3.0), Vec3(0.0, 1.6, 30.0)))
    }

    @Test
    fun `no hulls means nothing to block`() {
        assertFalse(Sightline.crosses(emptyList(), Vec3(0.0, 1.6, 0.0), Vec3(0.0, 1.6, 20.0)))
    }
}
