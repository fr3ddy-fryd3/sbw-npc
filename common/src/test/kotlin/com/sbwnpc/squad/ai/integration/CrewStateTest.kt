package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.tactics.FireSource
import com.sbwnpc.squad.ai.tactics.FireSupport
import com.sbwnpc.squad.ai.tactics.FireTarget
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Noticing
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrewStateTest {
    private fun state() = CrewState(Noticing("mortar", LogGroup.FIRE_SUPPORT)) { _, _ -> 76 }

    @Test fun `preparation salvos are counted per order and start again on a new one`() {
        val state = state()
        repeat(3) { state.preparationFired("squad:1") }
        assertEquals(3, state.preparationShotsFor("squad:1"))
        assertEquals(0, state.preparationShotsFor("squad:2"))
        state.preparationFired("squad:2")
        assertEquals(1, state.preparationShotsFor("squad:2"))
        assertEquals(0, state.preparationShotsFor("squad:1"))
    }

    @Test fun `the carry point stands on the ground, short of the target by the redeploy range`() {
        val state = state()
        val target = FireTarget(FireSource.ATTACK_PREPARATION, "squad:1")
        val tube = Vec3(0.0, 86.0, 0.0)
        val aim = Vec3(280.0, 63.0, 0.0)
        state.report(target, tube, aim, reachable = false, now = 0)
        state.report(target, tube, aim, reachable = false, now = FireSupport.DISPLACE_AFTER_TICKS)
        val carry = state.reachPoint!!
        assertEquals(76.0, carry.y)
        assertEquals(280.0 - FireSupportWorld.REDEPLOY_RANGE, carry.x, 1e-6)
        assertTrue(FireSupportWorld.REDEPLOY_RANGE < 200.0)
    }
}
