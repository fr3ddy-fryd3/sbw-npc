package com.sbwnpc.squad.ai.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A held spot without a shot gets one more look before he leaves it, and a stale miss never counts as that look. */
class LostShotGraceTest {
    @Test fun `the first miss keeps the spot`() {
        assertTrue(LostShotGrace.grants(null, 500))
    }

    @Test fun `a second miss at the recheck drops the spot`() {
        assertFalse(LostShotGrace.grants(500, 500 + LostShotGrace.RECHECK_TICKS))
        assertFalse(LostShotGrace.grants(500, 500 + LostShotGrace.SECOND_LOOK_WINDOW))
    }

    @Test fun `a miss long after the last one starts a fresh grace`() {
        assertTrue(LostShotGrace.grants(500, 500 + LostShotGrace.SECOND_LOOK_WINDOW + 1))
    }
}
