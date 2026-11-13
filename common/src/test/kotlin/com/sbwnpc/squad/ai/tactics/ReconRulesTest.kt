package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.tactics.ReconRules.Company
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconRulesTest {
    @Test fun `hidden recon holds its fire on every order and in every company`() {
        Company.entries.forEach { company ->
            assertTrue(ReconRules.holdsFire(company, foundEnemy = false, discovered = false, reported = true))
        }
        assertTrue(ReconRules.holdsFire(Company.INFANTRY, foundEnemy = true, discovered = false, reported = true))
        assertTrue(ReconRules.holdsFire(Company.SUPPORT, foundEnemy = true, discovered = false, reported = true))
    }

    @Test fun `discovered it fires, and a lone recon squad ordered to attack fires at the enemy it found`() {
        Company.entries.forEach { assertFalse(ReconRules.holdsFire(it, foundEnemy = false, discovered = true, reported = false)) }
        assertFalse(ReconRules.holdsFire(Company.ALONE, foundEnemy = true, discovered = false, reported = true))
    }

    @Test fun `a man at arm's length is answered whatever the rule`() {
        assertFalse(ReconRules.mayFire(holds = true, distance = 20.0))
        assertTrue(ReconRules.mayFire(holds = true, distance = 4.0))
        assertTrue(ReconRules.mayFire(holds = false, distance = 60.0))
    }

    @Test fun `recon beside infantry hides until the assault and fights once it begins`() {
        assertTrue(ReconRules.holdsFire(Company.INFANTRY, true, false, true, assault = false))
        assertFalse(ReconRules.holdsFire(Company.INFANTRY, false, false, true, assault = true))
        assertTrue(ReconRules.holdsFire(Company.SUPPORT, false, false, true, assault = true))
    }

    @Test fun `discovered it withdraws unless infantry is beside it or it is alone attacking a found enemy`() {
        assertFalse(ReconRules.retreats(Company.INFANTRY, true, false))
        assertFalse(ReconRules.retreats(Company.ALONE, true, true))
        assertTrue(ReconRules.retreats(Company.ALONE, false, true))
        assertTrue(ReconRules.retreats(Company.SUPPORT, true, true))
    }

    @Test fun `the wait lasts while the scouts are on their way and a minute at the post`() {
        assertTrue(ReconRules.awaitsIntel(3000, null, reconHidden = true, reported = false, engaged = false))
        assertTrue(ReconRules.awaitsIntel(3000, ReconRules.INTEL_WAIT_TICKS - 1, reconHidden = true, reported = false, engaged = false))
        assertFalse(ReconRules.awaitsIntel(3000, ReconRules.INTEL_WAIT_TICKS, reconHidden = true, reported = false, engaged = false))
        assertFalse(ReconRules.awaitsIntel(ReconRules.INTEL_CAP_TICKS, null, reconHidden = true, reported = false, engaged = false))
        assertFalse(ReconRules.awaitsIntel(0, null, reconHidden = true, reported = true, engaged = false))
        assertFalse(ReconRules.awaitsIntel(0, null, reconHidden = false, reported = false, engaged = false))
    }

    @Test fun `infantry fired on or losing a man stops waiting for the report`() {
        assertTrue(ReconRules.awaitsIntel(100, 100, reconHidden = true, reported = false, engaged = false))
        assertFalse(ReconRules.awaitsIntel(100, 100, reconHidden = true, reported = false, engaged = true))
        assertFalse(ReconRules.awaitsIntel(100, null, reconHidden = true, reported = false, engaged = true))
    }

    @Test fun `infantry holding short for the reports holds its fire unless fired on`() {
        assertTrue(ReconRules.infantryHoldsFire(holdingShort = true, underFire = false))
        assertFalse(ReconRules.infantryHoldsFire(holdingShort = true, underFire = true))
        assertFalse(ReconRules.infantryHoldsFire(holdingShort = false, underFire = false))
    }

    @Test fun `four wet blocks in a row are water to cross, a puddle is not`() {
        assertTrue(ReconRules.waterBetween(listOf(false, true, true, true, true, false)))
        assertFalse(ReconRules.waterBetween(listOf(true, true, true, false, true, true, true)))
    }

    @Test fun `alone it holds its fire until it has reported`() {
        assertTrue(ReconRules.holdsFire(Company.ALONE, foundEnemy = true, discovered = false, reported = false))
        assertFalse(ReconRules.holdsFire(Company.ALONE, foundEnemy = true, discovered = false, reported = true))
    }

    @Test fun `beside infantry in contact it keeps its post until the assault or its discovery`() {
        assertTrue(ReconRules.keepsPost(Company.INFANTRY, holdsFire = false, discovered = false, assault = false))
        assertFalse(ReconRules.keepsPost(Company.INFANTRY, holdsFire = false, discovered = false, assault = true))
        assertFalse(ReconRules.keepsPost(Company.INFANTRY, holdsFire = false, discovered = true, assault = false))
        Company.entries.forEach { assertTrue(ReconRules.keepsPost(it, holdsFire = true, discovered = false, assault = true)) }
    }

    @Test fun `without infantry it keeps its post exactly while it holds its fire`() {
        listOf(Company.ALONE, Company.SUPPORT).forEach { company ->
            listOf(true, false).forEach { holds -> assertTrue(ReconRules.keepsPost(company, holds, discovered = false, assault = false) == holds) }
        }
    }
}
