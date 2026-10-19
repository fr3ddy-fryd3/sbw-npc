package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DroneAccuracyTest {
    @Test
    fun `ordinary targets retain their original spread`() {
        for (spread in listOf(0.0, 0.1, 3.0 / 1.4, 3.0, 4.0, 5.5, 7.0)) {
            assertEquals(spread, DroneAccuracy.adjustSpread(spread, false))
        }
    }

    @Test
    fun `drone penalty affects every infantry rank while preserving rank advantage`() {
        val baseSpreads = listOf(3.0 / 1.4, 3.0, 4.0, 5.5, 7.0)
        val adjusted = baseSpreads.map { DroneAccuracy.adjustSpread(it, true) }
        baseSpreads.zip(adjusted).forEach { (base, spread) -> assertEquals(base * 2.5, spread) }
        assertTrue(adjusted.zipWithNext().all { (better, worse) -> better < worse })
    }

    @Test
    fun `zero spread and precise turret weapons also miss drones`() {
        assertEquals(3.0, DroneAccuracy.adjustSpread(0.0, true))
        assertEquals(3.0, DroneAccuracy.adjustSpread(0.1, true))
    }
}
