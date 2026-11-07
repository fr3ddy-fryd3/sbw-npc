package com.sbwnpc.squad.experience

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The blame an operator carries for his drone's sourceless crash blast. */
class ScopedTest {
    @Test fun `the value holds only inside the block`() {
        val scoped = Scoped<String>()
        assertEquals("op", scoped.with("op") { scoped.current })
        assertNull(scoped.current)
    }

    @Test fun `a nested blast hands the blame back to the outer operator`() {
        val scoped = Scoped<String>()
        scoped.with("first") {
            assertEquals("second", scoped.with("second") { scoped.current })
            assertEquals("first", scoped.current)
        }
        assertNull(scoped.current)
    }

    @Test fun `a block that throws leaves no blame behind`() {
        val scoped = Scoped<String>()
        assertThrows<IllegalStateException> { scoped.with("op") { error("boom") } }
        assertNull(scoped.current)
    }
}
