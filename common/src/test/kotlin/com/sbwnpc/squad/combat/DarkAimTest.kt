package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DarkAimTest {
    @Test fun `full light leaves the spread, the dark leaves 0_6 of the hits`() {
        assertEquals(1.0, DarkAim.forLight(15), 1e-9)
        assertEquals(1.0, DarkAim.forLight(12), 1e-9)
        // Hits go with the square of the spread: 0.6 of them is a spread 1/sqrt(0.6) wider.
        val black = DarkAim.forLight(0)
        assertEquals(0.6, 1.0 / (black * black), 1e-9)
    }

    @Test fun `each darker band scatters wider`() {
        val bands = listOf(15, 10, 7, 4, 1).map(DarkAim::forLight)
        assertTrue(bands.zipWithNext().all { (lighter, darker) -> darker > lighter }, "$bands")
    }
}
