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

    @Test
    fun `phonetic names are used first`() {
        assertEquals("Charlie", SquadManager.firstFreeName(setOf("Alpha", "Bravo"), ""))
    }

    @Test
    fun `past the phonetic names the number counts up to the first free one`() {
        val phonetic = setOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel")
        assertEquals("Squad 9", SquadManager.firstFreeName(phonetic, ""))
        assertEquals("Squad 10", SquadManager.firstFreeName(phonetic + "Squad 9", ""))
        assertEquals("Squad 9", SquadManager.firstFreeName(phonetic + "Squad 10", ""))
    }

    @Test
    fun `a prefix is part of the name it checks`() {
        val tanks = setOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel").map { "Tank $it" }.toSet()
        assertEquals("Tank Squad 10", SquadManager.firstFreeName(tanks + "Tank Squad 9", "Tank "))
        // An unprefixed "Squad 9" doesn't take the tank's number.
        assertEquals("Tank Squad 9", SquadManager.firstFreeName(tanks + "Squad 9", "Tank "))
    }
}
