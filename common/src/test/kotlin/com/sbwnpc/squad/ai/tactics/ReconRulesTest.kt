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
}
