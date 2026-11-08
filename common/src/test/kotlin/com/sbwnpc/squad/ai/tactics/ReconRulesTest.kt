package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.tactics.ReconRules.Company
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconRulesTest {
    @Test fun `hidden recon holds its fire on every order and in every company`() {
        Company.entries.forEach { company ->
            assertTrue(ReconRules.holdsFire(company, attack = false, sawEnemy = true, discovered = false))
            assertTrue(ReconRules.holdsFire(company, attack = true, sawEnemy = false, discovered = false))
        }
        assertTrue(ReconRules.holdsFire(Company.INFANTRY, attack = true, sawEnemy = true, discovered = false))
        assertTrue(ReconRules.holdsFire(Company.SUPPORT, attack = true, sawEnemy = true, discovered = false))
    }

    @Test fun `discovered it fires, and a lone recon squad ordered to attack fires at the enemy it found`() {
        Company.entries.forEach { assertFalse(ReconRules.holdsFire(it, attack = false, sawEnemy = false, discovered = true)) }
        assertFalse(ReconRules.holdsFire(Company.ALONE, attack = true, sawEnemy = true, discovered = false))
    }

    @Test fun `a man at arm's length is answered whatever the rule`() {
        assertFalse(ReconRules.mayFire(holds = true, distance = 20.0))
        assertTrue(ReconRules.mayFire(holds = true, distance = 4.0))
        assertTrue(ReconRules.mayFire(holds = false, distance = 60.0))
    }

    @Test fun `after its report recon fights beside infantry and keeps hiding otherwise`() {
        assertFalse(ReconRules.holdsFire(Company.INFANTRY, true, false, false, reported = true))
        assertTrue(ReconRules.holdsFire(Company.SUPPORT, true, false, false, reported = true))
    }

    @Test fun `discovered it withdraws unless infantry is beside it or it is alone attacking a found enemy`() {
        assertFalse(ReconRules.retreats(Company.INFANTRY, true, false))
        assertFalse(ReconRules.retreats(Company.ALONE, true, true))
        assertTrue(ReconRules.retreats(Company.ALONE, false, true))
        assertTrue(ReconRules.retreats(Company.SUPPORT, true, true))
    }

    @Test fun `the wait lasts while the scouts are on their way and a minute at the post`() {
        assertTrue(ReconRules.awaitsIntel(3000, null, reconHidden = true, reported = false))
        assertTrue(ReconRules.awaitsIntel(3000, ReconRules.INTEL_WAIT_TICKS - 1, reconHidden = true, reported = false))
        assertFalse(ReconRules.awaitsIntel(3000, ReconRules.INTEL_WAIT_TICKS, reconHidden = true, reported = false))
        assertFalse(ReconRules.awaitsIntel(ReconRules.INTEL_CAP_TICKS, null, reconHidden = true, reported = false))
        assertFalse(ReconRules.awaitsIntel(0, null, reconHidden = true, reported = true))
        assertFalse(ReconRules.awaitsIntel(0, null, reconHidden = false, reported = false))
    }

    @Test fun `four wet blocks in a row are water to cross, a puddle is not`() {
        assertTrue(ReconRules.waterBetween(listOf(false, true, true, true, true, false)))
        assertFalse(ReconRules.waterBetween(listOf(true, true, true, false, true, true, true)))
    }
}
