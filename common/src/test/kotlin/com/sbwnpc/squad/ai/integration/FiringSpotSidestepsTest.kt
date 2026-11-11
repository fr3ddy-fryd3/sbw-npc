package com.sbwnpc.squad.ai.integration

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FiringSpotSidestepsTest {
    private val enemy = Vec3(15.0, 0.0, 0.0)
    private val man = Vec3(0.0, 0.0, 0.0)

    @Test fun `a straight walk to every sidestep keeps him at his distance from the enemy or farther`() {
        val spots = sidesteps(man, enemy)
        assertTrue(spots.size >= 6, "spots=$spots")
        for (spot in spots) {
            for (step in 0..20) {
                val point = man.add(spot.subtract(man).scale(step / 20.0))
                assertTrue(point.distanceTo(enemy) >= 15.0 - 1.0e-9, "spot=$spot point=$point")
            }
        }
    }

    @Test fun `sidesteps lie on both sides of his bearing and within reach`() {
        val spots = sidesteps(man, enemy)
        assertTrue(spots.any { it.z > 4.0 } && spots.any { it.z < -4.0 }, "spots=$spots")
        assertTrue(spots.all { it.distanceTo(man) <= 20.0 }, "spots=$spots")
    }

    @Test fun `no sidesteps when he stands on his target`() {
        assertTrue(sidesteps(enemy, enemy).isEmpty())
    }
}
