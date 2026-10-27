package com.sbwnpc.squad.integration.journeymap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JourneyMap on Fabric only loads a plugin named by a `journeymap` entrypoint in `fabric.mod.json`
 * and then rejects the class unless it carries `@JourneyMapPlugin` (a NullPointerException in its log).
 * Without either the squad squares and order menu silently never appear, which is how the NeoForge-only
 * plugin went unnoticed on Fabric. The class cannot be loaded here (JourneyMap is not on the test
 * classpath), so the compiled file is read instead.
 */
class JourneyMapRegistrationTest {
    private val entrypoint = Regex("\"journeymap\"\\s*:\\s*\\[[^\\]]*?\"value\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)

    @Test
    fun `fabric mod json names the journeymap plugin`() {
        val json = requireNotNull(javaClass.getResource("/fabric.mod.json")) { "fabric.mod.json is not on the classpath" }.readText()
        val plugin = requireNotNull(entrypoint.find(json)) { "fabric.mod.json has no journeymap entrypoint" }.groupValues[1]
        assertEquals("com.sbwnpc.squad.integration.journeymap.SquadMapPlugin", plugin)

        val bytes = javaClass.getResourceAsStream("/" + plugin.replace('.', '/') + ".class")?.readBytes()
        assertNotNull(bytes, "$plugin is not compiled into the mod")
        val text = String(bytes!!, Charsets.ISO_8859_1)
        assertTrue(text.contains("journeymap/api/v2/client/IClientPlugin"), "$plugin does not implement IClientPlugin")
        assertTrue(text.contains("Ljourneymap/api/v2/common/JourneyMapPlugin;"), "$plugin lacks @JourneyMapPlugin")
    }
}
