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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import java.util.UUID

class SquadManagerTest {
    private fun manager(vararg squads: Squad): SquadManager {
        val tag = CompoundTag()
        val list = ListTag()
        squads.forEach { list.add(it.save()) }
        tag.put("Squads", list)
        return SquadManager.load(tag)
    }

    @Test
    fun `deleting an unloaded squad survives a restart without deleting another squad`() {
        val deleted = garrison(listOf(RIFLEMAN, SNIPER))
        val kept = garrison(listOf(MEDIC))
        val members = List(2) { UUID.randomUUID() }
        deleted.members.addAll(members)
        val otherMember = UUID.randomUUID()
        kept.members.add(otherMember)
        var mgr = manager(deleted, kept)
        assertEquals(members, mgr.beginDeletion(deleted.id))
        assertNull(mgr.get(deleted.id))
        mgr = SquadManager.load(mgr.saveState(CompoundTag()))
        assertNull(mgr.get(deleted.id))
        assertEquals(listOf(otherMember), mgr.get(kept.id)!!.members)
        assertTrue(mgr.takeDeletion(members[0], null))
        assertFalse(mgr.takeDeletion(otherMember, null))
        // The remaining member is still scheduled even after saving the first one's removal.
        mgr = SquadManager.load(mgr.saveState(CompoundTag()))
        assertFalse(mgr.takeDeletion(members[0], null))
        assertTrue(mgr.takeDeletion(members[1], null))
    }

    @Test
    fun `a deleted crewman loading later schedules its unloaded vehicle across a restart`() {
        val squad = garrison(listOf(NpcClass.TANK_CREW, NpcClass.TANK_CREW))
        val crew = List(2) { UUID.randomUUID() }
        squad.members.addAll(crew)
        var mgr = manager(squad)
        mgr.beginDeletion(squad.id)
        mgr = SquadManager.load(mgr.saveState(CompoundTag()))
        val vehicle = UUID.randomUUID()
        assertTrue(mgr.takeDeletion(crew[0], vehicle))
        assertTrue(mgr.takeDeletion(crew[1], vehicle))
        mgr = SquadManager.load(mgr.saveState(CompoundTag()))
        assertTrue(mgr.takeDeletion(vehicle, null))
        assertFalse(mgr.takeDeletion(vehicle, null))
    }

    @Test
    fun `legacy saves and NPCs without a deletion request remain untouched`() {
        val squad = garrison(listOf(RIFLEMAN))
        val member = UUID.randomUUID()
        squad.members.add(member)
        val mgr = manager(squad)
        assertFalse(mgr.takeDeletion(member, UUID.randomUUID()))
        assertFalse(mgr.takeDeletion(UUID.randomUUID(), null))
        assertTrue(mgr.beginDeletion(UUID.randomUUID()).isEmpty())
        assertEquals(listOf(member), mgr.get(squad.id)!!.members)
    }

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
