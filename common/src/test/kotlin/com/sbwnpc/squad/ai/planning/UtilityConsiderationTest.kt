package com.sbwnpc.squad.ai.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UtilityConsiderationTest {
    @Test fun `curves are clamped and fall when reversed`() {
        assertEquals(0.0, ResponseCurve.linear(0.2, 0.6).at(0.0))
        assertEquals(1.0, ResponseCurve.linear(0.2, 0.6).at(1.0))
        assertEquals(0.5, ResponseCurve.linear(0.2, 0.6).at(0.4), 1e-9)
        assertEquals(1.0, ResponseCurve.linear(1.0, 0.0).at(0.0))
        assertEquals(0.7, ResponseCurve.floored(0.7, 0.0, 1.0).at(-5.0), 1e-9)
        assertEquals(0.5, ResponseCurve.logistic(0.4, 10.0).at(0.4), 1e-9)
    }

    @Test fun `a consideration at zero vetoes, at one or unknown it leaves the base`() {
        val curve = ResponseCurve.linear(0.0, 1.0)
        assertEquals(0.0, UtilityScore.product(0.9, listOf(Consideration("a", 0.0, curve), Consideration("b", 1.0, curve))).value)
        assertEquals(0.9, UtilityScore.product(0.9, listOf(Consideration("a", 1.0, curve), Consideration("b", null, curve))).value, 1e-9)
        assertTrue(UtilityScore.product(0.9, listOf(Consideration("b", null, curve))).factors.single { it.name == "b" }.unknown)
    }

    @Test fun `compensation keeps an option with more factors comparable`() {
        val half = ResponseCurve { 0.5 }
        val one = UtilityScore.product(1.0, listOf(Consideration("a", 0.0, half))).value
        val three = UtilityScore.product(1.0, List(3) { Consideration("f$it", 0.0, half) }).value
        assertEquals(0.5, one, 1e-9)
        // Uncompensated three halves would be 0.125.
        assertTrue(three > 0.2, "three=$three")
    }

    @Test fun `the trace shows input and value of each factor`() {
        val score = UtilityScore.product(0.8, listOf(Consideration("ammo", 0.25, ResponseCurve.linear(0.0, 0.5)), Consideration("force", null, ResponseCurve.linear(0.0, 1.0))))
        assertEquals("0.50 [ammo 0.25>0.50 force?]", score.explain())
    }
}
