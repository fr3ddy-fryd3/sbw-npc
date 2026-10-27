package com.sbwnpc.squad.client

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Minecraft 1.21 draws a screen's background itself and 1.20.1 does not, so every screen of the
 * mod extends [SquadScreen], which draws it where the version needs it. A screen that extends
 * `Screen` directly would have no background on Forge.
 */
class ScreenBaseTest {
    private val screens = File("src/client/kotlin/com/sbwnpc/squad/client/screen")

    @Test
    fun `every screen extends the mod's base`() {
        assertTrue(screens.isDirectory, "Sources not found at ${screens.absolutePath}")
        val offenders = screens.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.useLines { lines -> lines.any { DIRECT_SUBCLASS.containsMatchIn(it) } } }
            .map { it.name }
            .toList()
        assertTrue(offenders.isEmpty(), "Extend SquadScreen, not Screen, so the background is drawn on every version: $offenders")
    }

    private companion object {
        val DIRECT_SUBCLASS = Regex("""\)\s*:\s*Screen\(|\bclass\s+\w+\s*:\s*Screen\(""")
    }
}
