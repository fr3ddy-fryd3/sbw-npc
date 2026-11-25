package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalRulesTest {
    private val home = Vec3(0.0, 0.0, 0.0)
    private val man = UUID(1, 1)

    private fun view(order: SquadOrder = SquadOrder.DEFEND, reach: Map<UUID, Double> = emptyMap()) = TacticalSnapshot(0, order, 1, home, home, emptyList(), emptyList(), reach = reach)

    private fun at(distance: Double) = Vec3(distance, 0.0, 0.0)

    @Test
    fun `a post outside the area is no farther than where the search began`() {
        assertTrue(TacticalRules.withinOrder(view(), at(80.0), 20.0, at(80.0), man))
        assertFalse(TacticalRules.withinOrder(view(), at(86.0), 20.0, at(80.0), man))
    }

    @Test
    fun `the distance where the contact found him widens the bound, no farther`() {
        val reach = view(reach = mapOf(man to 86.0))
        assertTrue(TacticalRules.withinOrder(reach, at(86.0), 20.0, at(80.0), man))
        assertFalse(TacticalRules.withinOrder(reach, at(98.0), 20.0, at(80.0), man))
    }

    @Test
    fun `another man's reach does not widen his bound`() {
        assertFalse(TacticalRules.withinOrder(view(reach = mapOf(UUID(2, 2) to 99.0)), at(86.0), 20.0, at(80.0), man))
    }

    @Test
    fun `other orders and the area itself are unchanged`() {
        assertTrue(TacticalRules.withinOrder(view(SquadOrder.ATTACK), at(500.0), 20.0, at(80.0), man))
        assertTrue(TacticalRules.withinOrder(view(), at(15.0), 20.0, at(80.0), man))
    }
}
