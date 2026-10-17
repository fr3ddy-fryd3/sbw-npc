package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AttackPhaseTest {
    private fun inputs(ready: Int, enemies: Int, peak: Int = enemies, quiet: Long = 0, engaged: Long = 400, silent: Long = 0, occupied: Boolean = false, threats: Int = 0, aborted: Boolean = false, peakThreats: Int = threats) = AttackPhase.Inputs(ready, enemies, peak, quiet, engaged, silent, occupied, threats, aborted, peakThreats)

    @Test fun `an untouched defence is not assaulted on numbers alone`() {
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 16, enemies = 4, engaged = 0)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 16, enemies = 4, engaged = 400)))
    }

    @Test fun `assault needs a two to one advantage after the firing line has thinned or silenced the defence`() {
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 8, enemies = 4, peak = 8)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 8, enemies = 4, silent = 300)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 7, enemies = 4, peak = 8)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 8, enemies = 4, peak = 8, engaged = 399)))
    }

    @Test fun `three to one starts the assault once the firing line has worked twice as long`() {
        // In-game 17.10: 48 attackers never assaulted 16 defenders who neither thinned nor fell silent (#54).
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 48, enemies = 16, engaged = 799)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 48, enemies = 16, engaged = 800)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 47, enemies = 16, engaged = 2000)))
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
        assertEquals(AttackPhase.CLEAR, AttackPhase.ASSAULT.next(inputs(ready = 6, enemies = 4, occupied = true, threats = 1)))
        assertEquals(AttackPhase.CLEAR, AttackPhase.DEPLOY.next(inputs(ready = 14, enemies = 9, occupied = true, threats = 2)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.CLEAR.next(inputs(ready = 3, enemies = 0, occupied = true, threats = 0)))
    }

    @Test fun `standing on the point against a defence that outnumbers them is not taking it`() {
        // In-game 16.10, 20:12:37: DEPLOY to CLEAR on occupation with 28 against 16 at the point,
        // back to DEPLOY 4 s later on the ratio, and again on the next occupation.
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 1, enemies = 9, occupied = true, threats = 2)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.CLEAR.next(inputs(ready = 25, enemies = 20, occupied = true, threats = 23)))
    }

    @Test fun `defenders stepping back from the point have not thinned`() {
        // In-game 16.10, 20:12:07: 8 of a peak of 19 at the point but 11 to 21 round it the whole
        // time; the assault met 16 to 20 of them 10 s later.
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 31, enemies = 8, peak = 19, threats = 11, peakThreats = 21)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 31, enemies = 8, peak = 19, threats = 10, peakThreats = 21)))
    }

    @Test fun `a pause between bursts is not a silenced defence`() {
        // In-game 16.10, 17:16:00: assault after 5 s without fire on 16 against 5 seen, called off
        // 3 s later when 12 showed up.
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 16, enemies = 5, peak = 7, engaged = 510, silent = 100)))
    }

    @Test fun `after a called off assault only a thinned defence is assaulted again`() {
        // In-game 16.10, 17:11:34: 20 against 10 seen of a peak of 15 went again after a silence
        // and was called off within 3 s.
        assertEquals(AttackPhase.DEPLOY, AttackPhase.DEPLOY.next(inputs(ready = 20, enemies = 10, peak = 15, engaged = 900, silent = 400, aborted = true)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.DEPLOY.next(inputs(ready = 12, enemies = 6, peak = 15, engaged = 1990, silent = 0, aborted = true)))
    }

    @Test fun `clearing keeps on while the clearing team leaves the holders short`() {
        // In-game 16.10, 17:12:48: present flipped 5/6 and 6/6 and the phase with it every update.
        assertEquals(AttackPhase.CLEAR, AttackPhase.CLEAR.next(inputs(ready = 12, enemies = 0, peak = 15, occupied = false, threats = 2)))
        assertEquals(AttackPhase.ASSAULT, AttackPhase.CLEAR.next(inputs(ready = 12, enemies = 0, peak = 15, occupied = false, threats = 0)))
        assertEquals(AttackPhase.DEPLOY, AttackPhase.CLEAR.next(inputs(ready = 5, enemies = 4, occupied = false, threats = 4)))
    }
}
