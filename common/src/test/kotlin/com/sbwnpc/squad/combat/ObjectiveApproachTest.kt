package com.sbwnpc.squad.combat

import com.sbwnpc.squad.squad.SquadOrder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObjectiveApproachTest {
    private fun cautious(order: SquadOrder = SquadOrder.ATTACK, distance: Double = 80.0, onFoot: Boolean = true, spent: Boolean = false) = ObjectiveApproach.cautious(order, distance, onFoot, spent)

    @Test fun `attack and move within a hundred blocks are cautious`() {
        assertTrue(cautious(SquadOrder.ATTACK))
        assertTrue(cautious(SquadOrder.MOVE))
        assertTrue(cautious(distance = ObjectiveApproach.RANGE))
    }

    @Test fun `beyond a hundred blocks and other orders are not`() {
        assertFalse(cautious(distance = ObjectiveApproach.RANGE + 1))
        assertFalse(cautious(SquadOrder.DEFEND))
        assertFalse(cautious(SquadOrder.RETREAT))
    }

    @Test fun `men in vehicles and men who had their contact are not`() {
        assertFalse(cautious(onFoot = false))
        assertFalse(cautious(spent = true))
    }
}
