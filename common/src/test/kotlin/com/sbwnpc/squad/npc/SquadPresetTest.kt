package com.sbwnpc.squad.npc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The infantry presets keep their support roster: shotgunners were added without taking a sniper, gunner or medic away. */
class SquadPresetTest {
    private val roles = listOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.SNIPER, NpcClass.MACHINE_GUNNER, NpcClass.MEDIC)

    private fun roster(preset: SquadPreset) = roles.map { role -> preset.composition.count { it == role } }

    @Test fun `each infantry preset deploys its stated roster and nothing else`() {
        assertEquals(listOf(3, 1, 0, 0, 1), roster(SquadPreset.FIVE))
        assertEquals(listOf(3, 1, 1, 1, 1), roster(SquadPreset.SEVEN))
        assertEquals(listOf(9, 1, 2, 2, 2), roster(SquadPreset.SIXTEEN))
        assertEquals(listOf(18, 2, 4, 4, 4), roster(SquadPreset.THIRTY_TWO))
        listOf(SquadPreset.FIVE, SquadPreset.SEVEN, SquadPreset.SIXTEEN, SquadPreset.THIRTY_TWO).forEach {
            assertEquals(roster(it).sum(), it.composition.size, "$it has a role outside the infantry roster")
        }
    }

    @Test fun `recon is three scouts, deployed in its grid`() {
        assertEquals(List(3) { NpcClass.SCOUT }, SquadPreset.RECON.composition)
        assertTrue(SquadPreset.RECON.grid)
        assertEquals(SquadPreset.RECON, SquadPreset.THIRTY_TWO.next())
    }

    @Test fun `the removed six-man preset loads as the standard seven`() {
        assertEquals(SquadPreset.SEVEN, SquadPreset.byOrdinal(SquadPreset.REMOVED_SIX.ordinal))
    }
}
