package com.sbwnpc.squad

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * SuperbWarfare may not stay around, so everything the addon knows about it lives in one place:
 * the adapter under `integration/sbw/` (plus the mixins, which patch SBW classes by definition).
 * Everything else reaches SBW through the ports in `domain/port/`.
 */
class SbwBoundaryTest {
    private val kotlinRoot = File(System.getProperty("sbwnpc.projectDir", "."), "src/main/kotlin/com/sbwnpc/squad")

    @Test
    fun `nothing outside the adapter uses SuperbWarfare`() {
        assertTrue(kotlinRoot.isDirectory, "Sources not found at $kotlinRoot")
        val offenders = kotlinRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.useLines { lines -> lines.any { "com.atsuishio." in it } } }
            .map { it.relativeTo(kotlinRoot).invariantSeparatorsPath }
            .filterNot { it.startsWith(ADAPTER) }
            .toList()
        assertTrue(offenders.isEmpty(), "Use SBW only in $ADAPTER, go through a port instead: $offenders")
    }

    private companion object {
        const val ADAPTER = "integration/sbw/"
    }
}
