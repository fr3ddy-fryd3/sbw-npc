package com.sbwnpc.squad.squad

import com.sbwnpc.squad.npc.NpcClass.MACHINE_GUNNER
import com.sbwnpc.squad.npc.NpcClass.MEDIC
import com.sbwnpc.squad.npc.NpcClass.RIFLEMAN
import com.sbwnpc.squad.npc.NpcClass.SNIPER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SquadManagerTest {
    @Test
    fun `barracks replaces the class that actually died`() {
        val composition = listOf(RIFLEMAN, RIFLEMAN, MACHINE_GUNNER, MEDIC)
        assertEquals(listOf(MACHINE_GUNNER), SquadManager.missingClasses(composition, listOf(RIFLEMAN, RIFLEMAN, MEDIC)))
    }

    @Test
    fun `unloaded members reserve a missing slot instead of being duplicated`() {
        val composition = listOf(RIFLEMAN, RIFLEMAN, SNIPER, MEDIC)
        assertEquals(1, SquadManager.missingClasses(composition, listOf(RIFLEMAN, null, MEDIC)).size)
    }
}
