package com.sbwnpc.squad.ai.tactics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class MedicalCareTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)

    @Test fun `critical first, then the most hurt, then the nearest`() {
        val wounded = listOf(Wounded(a, 0.5f, 5.0), Wounded(b, 0.25f, 40.0), Wounded(c, 0.25f, 10.0))
        assertEquals(c, MedicalCare.pick(null, wounded, fighting = false) { false })
        assertEquals(a, MedicalCare.pick(null, listOf(Wounded(a, 0.4f, 30.0), Wounded(b, 0.6f, 2.0)), fighting = false) { false })
    }

    @Test fun `a medic in a fight takes only critical patients`() {
        assertNull(MedicalCare.pick(null, listOf(Wounded(a, 0.5f, 5.0)), fighting = true) { false })
        assertEquals(b, MedicalCare.pick(null, listOf(Wounded(a, 0.5f, 5.0), Wounded(b, 0.2f, 50.0)), fighting = true) { false })
    }

    @Test fun `he keeps his patient while it needs him, and never takes one another medic holds`() {
        val wounded = listOf(Wounded(a, 0.6f, 30.0), Wounded(b, 0.1f, 5.0))
        assertEquals(a, MedicalCare.pick(a, wounded, fighting = false) { false })
        assertEquals(a, MedicalCare.pick(null, wounded, fighting = false) { it == b })
        // His patient healed past the threshold: he takes the next.
        assertEquals(b, MedicalCare.pick(a, listOf(Wounded(a, 0.75f, 30.0), Wounded(b, 0.1f, 5.0)), fighting = false) { false })
    }
}
