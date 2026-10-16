package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class AttackProgressTest {
    private fun AttackProgress.update(now: Long, ready: Int, enemies: Int, contact: Boolean = enemies > 0, incoming: Boolean = false, occupied: Boolean = false, threats: Int = enemies) = observe(now, contact, incoming, ready, enemies, occupied, threats)

    @Test fun `keeps the peak and engagement time between updates`() {
        val progress = AttackProgress(0)
        assertEquals(0L, progress.update(100, ready = 8, enemies = 0).ticksEngaged)
        progress.update(200, ready = 8, enemies = 8, incoming = true)
        val inputs = progress.update(700, ready = 8, enemies = 4, incoming = true)
        assertEquals(8, inputs.peakEnemiesAtObjective)
        assertEquals(500L, inputs.ticksEngaged)
        assertEquals(0L, inputs.ticksSinceIncoming)
        // Half the defence is gone after the firing line worked for 20 s: assault.
        assertEquals(AttackPhase.ASSAULT, progress.phase)
    }

    @Test fun `silence is measured from the start of the engagement until fire arrives`() {
        val progress = AttackProgress(0)
        progress.update(100, ready = 8, enemies = 4)
        assertEquals(400L, progress.update(500, ready = 8, enemies = 4).ticksSinceIncoming)
    }

    @Test fun `quiet after the last contact starts the assault`() {
        val progress = AttackProgress(0)
        progress.update(100, ready = 2, enemies = 4)
        progress.update(299, ready = 2, enemies = 0)
        assertEquals(AttackPhase.DEPLOY, progress.phase)
        progress.update(300, ready = 2, enemies = 0)
        assertEquals(AttackPhase.ASSAULT, progress.phase)
    }

    @Test fun `an approach that has not met the defence yet does not assault`() {
        // In-game 16.10: 16 attackers went DEPLOY to ASSAULT 207 ticks after the order with no
        // enemy ever seen, then ran into 16 defenders at the objective.
        val progress = AttackProgress(0)
        assertEquals(0L, progress.update(207, ready = 16, enemies = 0).ticksSinceContact)
        progress.update(600, ready = 16, enemies = 0)
        assertEquals(AttackPhase.DEPLOY, progress.phase)
        progress.update(700, ready = 30, enemies = 16)
        assertEquals(AttackPhase.DEPLOY, progress.phase)
    }

    @Test fun `the clearing team is kept between updates`() {
        val progress = AttackProgress(0)
        progress.update(100, ready = 8, enemies = 0, contact = true, occupied = true, threats = 1)
        assertEquals(AttackPhase.CLEAR, progress.phase)
        val ids = (1..8).map { UUID(0, it.toLong()) }
        val objective = Point3(100.0, 64.0, 100.0)
        val straggler = Point3(160.0, 64.0, 100.0)
        val first = progress.roles(objective, ids, ids.mapIndexed { i, id -> AttackMember(id, Point3(100.0 + i * 2, 64.0, 100.0), true) }, listOf(straggler))
        // The others are now nearer, but the team already sent stays.
        val second = progress.roles(objective, ids, ids.mapIndexed { i, id -> AttackMember(id, Point3(160.0 - i * 2, 64.0, 100.0), true) }, listOf(straggler))
        assertEquals(first.clearing.keys, second.clearing.keys)
    }
}
