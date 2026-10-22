package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurretGunnerAccuracyTest {
    @Test fun `every rank scatters wider than the weapon, a recruit most`() {
        val spreads = NpcRank.entries.map { TurretGunnerAccuracy.spread(0.5, it) }
        assertTrue(spreads.all { it > 0.5 })
        assertEquals(0.75, TurretGunnerAccuracy.spread(0.5, NpcRank.ELITE), 1e-9)
        assertEquals(2.0, TurretGunnerAccuracy.spread(0.5, NpcRank.RECRUIT), 1e-9)
        assertTrue(TurretGunnerAccuracy.spread(0.5, NpcRank.RECRUIT) > TurretGunnerAccuracy.spread(0.5, NpcRank.REGULAR))
    }
}
