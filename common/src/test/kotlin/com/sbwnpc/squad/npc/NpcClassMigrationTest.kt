package com.sbwnpc.squad.npc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Saved worlds store a class by ordinal: the merged mortar roles must load as MORTAR, and nothing after them may shift. */
class NpcClassMigrationTest {
    @Test fun `the old operator and loader ordinals load as the merged mortar role`() {
        assertEquals(NpcClass.MORTAR, NpcClass.byOrdinal(5))
        assertEquals(NpcClass.MORTAR, NpcClass.byOrdinal(6))
    }

    @Test fun `the roles after the legacy mortar pair keep their saved ordinals`() {
        assertEquals(7, NpcClass.TANK_CREW.ordinal)
        assertEquals(10, NpcClass.DRONE_OPERATOR.ordinal)
    }

    @Test fun `the legacy mortar roles are not offered to players`() {
        assertTrue(NpcClass.selectable.none { it.name == "MORTAR_OPERATOR" || it.name == "MORTAR_LOADER" })
    }
}
