package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.tactics.FireSource
import com.sbwnpc.squad.ai.tactics.FireSupport
import com.sbwnpc.squad.ai.tactics.FireTarget
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Noticing
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CrewStateTest {
    private fun state() = CrewState(Noticing("mortar", LogGroup.FIRE_SUPPORT)) { _, _ -> 76 }

    @Test fun `preparation salvos are counted per squad and order, and partners are decided once per order`() {
        val ledger = PreparationLedger()
        val squad = UUID(0, 1)
        assertTrue(ledger.partnered(squad, "squad:1") { true })
        // A partner finishing does not hand the mortar back unlimited shelling for the same order.
        assertTrue(ledger.partnered(squad, "squad:1") { false })
        repeat(3) { ledger.fired(squad, "squad:1") }
        assertEquals(3, ledger.shots(squad, "squad:1"))
        assertEquals(0, ledger.shots(UUID(0, 2), "squad:1"))
        assertFalse(ledger.partnered(squad, "squad:2") { false })
        assertEquals(0, ledger.shots(squad, "squad:2"))
        ledger.fired(squad, "squad:1")
        assertEquals(0, ledger.shots(squad, "squad:1"))
    }

    @Test fun `an unloaded chunk leaves the carry point at the target's height`() {
        val state = CrewState(Noticing("mortar", LogGroup.FIRE_SUPPORT)) { _, _ -> null }
        val target = FireTarget(FireSource.ATTACK_PREPARATION, "squad:1")
        state.report(target, Vec3(0.0, 86.0, 0.0), Vec3(280.0, 63.0, 0.0), reachable = false, now = 0)
        state.report(target, Vec3(0.0, 86.0, 0.0), Vec3(280.0, 63.0, 0.0), reachable = false, now = FireSupport.DISPLACE_AFTER_TICKS)
        assertEquals(63.0, state.reachPoint!!.y)
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
