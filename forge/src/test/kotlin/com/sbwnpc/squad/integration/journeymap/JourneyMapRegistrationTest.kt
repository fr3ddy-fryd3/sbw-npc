package com.sbwnpc.squad.integration.journeymap

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JourneyMap finds its plugins by the `@JourneyMapPlugin` annotation on the class. Without it the
 * squad squares and order menu silently never appear. The class cannot be loaded here (JourneyMap is
 * not on the test classpath), so the compiled file is read instead.
 */
class JourneyMapRegistrationTest {
    @Test
    fun `plugin class is annotated for journeymap`() {
        val bytes = javaClass.getResourceAsStream("/com/sbwnpc/squad/integration/journeymap/SquadMapPlugin.class")?.readBytes()
        assertNotNull(bytes, "SquadMapPlugin is not compiled into the mod")
        val text = String(bytes!!, Charsets.ISO_8859_1)
        assertTrue(text.contains("Ljourneymap/api/v2/common/JourneyMapPlugin;"), "SquadMapPlugin lacks @JourneyMapPlugin")
        assertTrue(text.contains("journeymap/api/v2/client/IClientPlugin"), "SquadMapPlugin does not implement IClientPlugin")
    }
}
