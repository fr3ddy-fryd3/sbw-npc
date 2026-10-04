package com.sbwnpc.squad.squad

import com.sbwnpc.squad.npc.NpcClass.MACHINE_GUNNER
import com.sbwnpc.squad.npc.NpcClass.MEDIC
import com.sbwnpc.squad.npc.NpcClass.RIFLEMAN
import com.sbwnpc.squad.npc.NpcClass.SNIPER
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class SquadManagerTest {
    private fun garrison(composition: List<NpcClass>) = Squad(
        UUID.randomUUID(), "Alpha", SquadFaction.DEFAULT, SquadOrder.DEFEND, mutableListOf(),
        null, null, UUID.randomUUID(), originalComposition = composition, rank = NpcRank.DEFAULT
    )

    @Test
    fun `a saved partial garrison grows one recruit at a time to the full configured strength`() {
        val composition = SquadPreset.THIRTY_TWO.composition
        var squad = garrison(composition)
        val present = mutableMapOf<UUID, NpcClass>()
        for ((index, expected) in composition.withIndex()) {
            // Reload between recruits: a one-man start must not become a one-man target strength.
            squad = Squad.load(squad.save())
            val recruit = SquadManager.nextReinforcement(listOf(squad), present::get)!!
            assertEquals(squad, recruit.first)
            assertEquals(expected, recruit.second)
            val id = UUID.randomUUID()
            squad.members.add(id)
            present[id] = recruit.second
            assertEquals(index + 1, squad.members.size)
        }
        assertEquals(composition, squad.members.map(present::get))
        assertNull(SquadManager.nextReinforcement(listOf(squad), present::get))
    }

    @Test
    fun `one barracks selects only one recruit across multiple assigned squads`() {
        val first = garrison(listOf(SNIPER))
        val second = garrison(listOf(MACHINE_GUNNER, MEDIC))
        val present = mutableMapOf<UUID, NpcClass>()
        assertEquals(first to SNIPER, SquadManager.nextReinforcement(listOf(first, second), present::get))
        val id = UUID.randomUUID()
        first.members.add(id)
        present[id] = SNIPER
        assertEquals(second to MACHINE_GUNNER, SquadManager.nextReinforcement(listOf(first, second), present::get))
        // An unloaded member reserves its slot; the other squad still gets a recruit.
        present.remove(id)
        assertEquals(second to MACHINE_GUNNER, SquadManager.nextReinforcement(listOf(first, second), present::get))
    }

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
