package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AttackPhaseTest {
    private fun inputs(ready: Int, enemies: Int, peak: Int = enemies, quiet: Long = 0, engaged: Long = 400, silent: Long = 0, occupied: Boolean = false, threats: Int = 0) = AttackPhase.Inputs(ready, enemies, peak, quiet, engaged, silent, occupied, threats)

    @Test fun `an untouched defence is not assaulted on numbers alone`() {
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 16, enemies = 4, engaged = 0)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 16, enemies = 4, engaged = 400)))
    }

    @Test fun `assault needs a two to one advantage after the firing line has thinned or silenced the defence`() {
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 8, enemies = 4, peak = 8)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 8, enemies = 4, silent = 100)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 7, enemies = 4, peak = 8)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 8, enemies = 4, peak = 8, engaged = 399)))
    }

    @Test fun `assault starts when no enemy was seen for ten seconds`() {
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 2, enemies = 4, quiet = 199, engaged = 0)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 2, enemies = 0, quiet = 200, engaged = 0)))
    }

    @Test fun `assault is called off only below the lower ratio`() {
        assertEquals(AttackPhase.ASSAULT, AttackPhase.ASSAULT.next(inputs(ready = 6, enemies = 4)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.ASSAULT.next(inputs(ready = 5, enemies = 4)))
    }

    @Test fun `taken objective with enemies around it is cleared`() {
        assertEquals(AttackPhase.CLEAR, AttackPhase.ASSAULT.next(inputs(ready = 3, enemies = 4, occupied = true, threats = 1)))
        assertEquals(AttackPhase.CLEAR, AttackPhase.DEPLOY.next(inputs(ready = 1, enemies = 9, occupied = true, threats = 2)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.CLEAR.next(inputs(ready = 3, enemies = 0, occupied = true, threats = 0)))
    }
}
