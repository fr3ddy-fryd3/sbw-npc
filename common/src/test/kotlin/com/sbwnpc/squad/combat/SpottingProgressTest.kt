package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SpottingProgressTest {
    private val enemy = UUID(1, 1)

    @Test fun `what takes under half a scan is noticed at the first look`() {
        assertTrue(SpottingProgress().advance(enemy, 100, required = 0.3))
    }

    @Test fun `a longer look fills over several scans`() {
        val progress = SpottingProgress()
        assertFalse(progress.advance(enemy, 100, required = 3.0), "0.5 s")
        assertFalse(progress.advance(enemy, 120, required = 3.0), "1.5 s")
        assertFalse(progress.advance(enemy, 140, required = 3.0), "2.5 s")
        assertTrue(progress.advance(enemy, 160, required = 3.0), "3.5 s")
    }

    @Test fun `a target out of range never fills`() {
        val progress = SpottingProgress()
        repeat(20) { assertFalse(progress.advance(enemy, 100L + it * 20, required = null)) }
    }

    @Test fun `progress drains while the target is out of sight and is dropped when empty`() {
        val progress = SpottingProgress()
        progress.advance(enemy, 100, required = 5.0)
        assertEquals(0.5, progress.seconds(enemy), 1e-9)
        progress.endScan(110)
        assertEquals(0.0, progress.seconds(enemy), 1e-9)
    }

    @Test fun `a long gap between looks counts as no more than two seconds`() {
        val progress = SpottingProgress()
        progress.advance(enemy, 0, required = 100.0)
        progress.advance(enemy, 10_000, required = 100.0)
        assertEquals(2.5, progress.seconds(enemy), 1e-9)
    }

    @Test fun `a target that gave itself away is noticed whatever the time needed`() {
        val progress = SpottingProgress()
        progress.fill(enemy, 100)
        assertTrue(progress.advance(enemy, 101, required = 30.0))
    }
}
