package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test fun `a called off assault is not retried on silence`() {
        val progress = AttackProgress(0)
        progress.update(0, ready = 23, enemies = 8, incoming = true)
        progress.update(400, ready = 23, enemies = 4, incoming = true)
        assertEquals(AttackPhase.ASSAULT, progress.phase)
        progress.update(420, ready = 21, enemies = 15, incoming = true)
        assertEquals(AttackPhase.DEPLOY, progress.phase)
        // Long silence, still 10 of a peak of 15 seen: stay on the firing line.
        progress.update(1000, ready = 20, enemies = 10)
        assertEquals(AttackPhase.DEPLOY, progress.phase)
        progress.update(1100, ready = 12, enemies = 6)
        assertEquals(AttackPhase.ASSAULT, progress.phase)
    }

    @Test fun `occupation flickering under the clearing team does not flip the phase`() {
        val progress = AttackProgress(0)
        progress.update(100, ready = 12, enemies = 0, contact = true, occupied = true, threats = 2)
        assertEquals(AttackPhase.CLEAR, progress.phase)
        for (tick in 110L..300L step 10) {
            progress.update(tick, ready = 12, enemies = 0, contact = true, occupied = tick % 20 == 0L, threats = 2)
            assertEquals(AttackPhase.CLEAR, progress.phase)
        }
    }

    @Test fun `the clearing team is kept between updates`() {
        val progress = AttackProgress(0)
        progress.update(100, ready = 8, enemies = 0, contact = true, occupied = true, threats = 1)
        assertEquals(AttackPhase.CLEAR, progress.phase)
        val ids = (1..8).map { UUID(0, it.toLong()) }
        val objective = Point3(100.0, 64.0, 100.0)
        val straggler = Point3(160.0, 64.0, 100.0)
        val first = progress.roles(100, objective, ids, ids.mapIndexed { i, id -> AttackMember(id, Point3(100.0 + i * 2, 64.0, 100.0), true) }, listOf(straggler))
        // The others are now nearer, but the team already sent stays.
        val second = progress.roles(110, objective, ids, ids.mapIndexed { i, id -> AttackMember(id, Point3(160.0 - i * 2, 64.0, 100.0), true) }, listOf(straggler))
        assertEquals(first.clearing.keys, second.clearing.keys)
    }

    private val objective = Point3(100.0, 64.0, 100.0)
    private val defender = Point3(100.0, 64.0, 130.0)
    private fun ids(n: Int) = (1..n).map { UUID(0, it.toLong()) }
    private fun at(ids: List<UUID>, z: Double) = ids.map { AttackMember(it, Point3(100.0, 64.0, z), true) }

    @Test fun `defenders out of sight are counted until forgotten or dead`() {
        val progress = AttackProgress(0)
        val (a, b, c) = ids(3)
        val seen = mapOf(a to objective, b to objective, c to objective)
        assertEquals(3, progress.defenders(0, seen) { true }.size)
        assertEquals(3, progress.defenders(100, mapOf(a to objective)) { true }.size)
        assertEquals(2, progress.defenders(200, mapOf(a to objective)) { it != c }.size)
        assertEquals(1, progress.defenders(601, mapOf(a to objective)) { true }.size)
    }

    @Test fun `a defence that only stepped out of sight is not assaulted`() {
        // In-game 17.10: 12 attackers assaulted 16 defenders when 5 were in sight (#54).
        val progress = AttackProgress(0)
        val defenders = ids(16).associateWith { objective }
        progress.update(0, ready = 12, enemies = progress.defenders(0, defenders) { true }.size, incoming = true)
        val inSight = defenders.entries.take(5).associate { it.toPair() }
        progress.update(500, ready = 12, enemies = progress.defenders(500, inSight) { true }.size)
        assertEquals(AttackPhase.DEPLOY, progress.phase)
    }

    private fun assaulting(ready: Int, enemies: Int): AttackProgress {
        val progress = AttackProgress(0)
        progress.update(0, ready = ready, enemies = enemies * 2, incoming = true)
        progress.update(400, ready = ready, enemies = enemies, incoming = true)
        assertEquals(AttackPhase.ASSAULT, progress.phase)
        return progress
    }

    @Test fun `a closed-in assault with the advantage goes onto the point after twenty seconds`() {
        val progress = assaulting(ready = 20, enemies = 4)
        val movers = ids(4)
        assertTrue(progress.roles(400, objective, movers, at(movers, 30.0), listOf(defender)).closing.isNotEmpty())
        // On their closing posts (half way to the defender) from tick 410.
        assertTrue(progress.roles(410, objective, movers, at(movers, 80.0), listOf(defender)).assault.isEmpty())
        assertTrue(progress.roles(809, objective, movers, at(movers, 80.0), listOf(defender)).assault.isEmpty())
        val roles = progress.roles(810, objective, movers, at(movers, 80.0), listOf(defender))
        assertTrue(progress.occupying)
        assertEquals(movers.toSet(), roles.assault.keys)
        assertTrue(roles.closing.isEmpty())
    }

    @Test fun `movers still on their way to their closing posts do not start the clock`() {
        val progress = assaulting(ready = 20, enemies = 4)
        val movers = ids(4)
        progress.roles(400, objective, movers, at(movers, 30.0), listOf(defender))
        assertTrue(progress.roles(1000, objective, movers, at(movers, 50.0), listOf(defender)).assault.isEmpty())
        assertFalse(progress.occupying)
    }

    @Test fun `an assault with the numbers goes onto the point after a minute even off its posts`() {
        // In-game 17.10 the movers never reached their closing posts and the clock never started.
        val progress = assaulting(ready = 20, enemies = 4)
        val movers = ids(4)
        assertTrue(progress.roles(400, objective, movers, at(movers, 30.0), listOf(defender)).assault.isEmpty())
        assertTrue(progress.roles(1599, objective, movers, at(movers, 50.0), listOf(defender)).assault.isEmpty())
        assertEquals(movers.toSet(), progress.roles(1600, objective, movers, at(movers, 50.0), listOf(defender)).assault.keys)
    }

    @Test fun `without a two to one advantage a closed-in assault keeps firing from its posts`() {
        val progress = assaulting(ready = 12, enemies = 4)
        // Not losing (12 >= 1.5 x 7), not twice the defenders either.
        progress.update(410, ready = 12, enemies = 7, incoming = true)
        assertEquals(AttackPhase.ASSAULT, progress.phase)
        val movers = ids(4)
        progress.roles(410, objective, movers, at(movers, 80.0), listOf(defender))
        assertTrue(progress.roles(1000, objective, movers, at(movers, 80.0), listOf(defender)).assault.isEmpty())
    }

    // 11 against 14 under fire: never an assault, see #65.
    private fun outnumbered(now: Long, sectorsHeld: Boolean = true) =
        AttackProgress(0).apply {
            observe(0, true, true, 11, 14, false, 14, sectorsHeld)
            observe(now, true, true, 11, 14, false, 14, sectorsHeld)
            assertEquals(AttackPhase.DEPLOY, phase)
        }

    private fun AttackProgress.post(now: Long, bounder: List<UUID>, z: Double) = bounds(now, objective, bounder.toSet(), at(bounder, z), listOf(defender)).values.single().z

    @Test fun `bounders close in only once the firing line has worked`() {
        val bounders = ids(2).toSet()
        val members = at(ids(4), 60.0)
        assertTrue(outnumbered(399).bounds(399, objective, bounders, members, listOf(defender)).isEmpty())
        // Half the 70 blocks to the defender; the other two keep the line.
        assertEquals(bounders.associateWith { Point3(100.0, 64.0, 95.0) }, outnumbered(400).bounds(400, objective, bounders, members, listOf(defender)))
    }

    @Test fun `bounders do not close in while the flanks are still on their way`() {
        val progress = outnumbered(1000, sectorsHeld = false)
        assertTrue(progress.bounds(1000, objective, ids(2).toSet(), at(ids(2), 60.0), listOf(defender)).isEmpty())
    }

    @Test fun `a bounder takes the next bound after firing from his post, down to the bound distance`() {
        val progress = outnumbered(400)
        val bounder = ids(1)
        assertEquals(95.0, progress.post(400, bounder, 60.0))
        assertEquals(95.0, progress.post(410, bounder, 93.0))
        assertEquals(95.0, progress.post(609, bounder, 93.0))
        // 37 blocks out, the next bound stops at 30.
        assertEquals(100.0, progress.post(610, bounder, 93.0))
        assertEquals(100.0, progress.post(620, bounder, 100.0))
        assertEquals(100.0, progress.post(900, bounder, 100.0))
    }

    @Test fun `a bounder who never reaches his post takes a new one from where he is`() {
        val progress = outnumbered(400)
        val bounder = ids(1)
        assertEquals(95.0, progress.post(400, bounder, 60.0))
        assertEquals(95.0, progress.post(999, bounder, 50.0))
        // Stuck 80 blocks out: half of it.
        assertEquals(90.0, progress.post(1000, bounder, 50.0))
    }

    @Test fun `a bounder nearer than the bound distance is sent back to it`() {
        val progress = outnumbered(400)
        // 20 blocks from the defender, as an assault called back leaves the men.
        assertEquals(100.0, progress.post(400, ids(1), 110.0))
    }

    @Test fun `back on the line after a called off assault the bounds wait for the line to work again`() {
        val progress = AttackProgress(0)
        progress.update(0, ready = 23, enemies = 8, incoming = true)
        progress.update(400, ready = 23, enemies = 4, incoming = true)
        assertEquals(AttackPhase.ASSAULT, progress.phase)
        progress.update(420, ready = 21, enemies = 15, incoming = true)
        assertEquals(AttackPhase.DEPLOY, progress.phase)
        val bounder = ids(1).toSet()
        assertTrue(progress.bounds(819, objective, bounder, at(ids(1), 60.0), listOf(defender)).isEmpty())
        assertEquals(1, progress.bounds(820, objective, bounder, at(ids(1), 60.0), listOf(defender)).size)
    }

    @Test fun `bounds are kept while no defender is known and dropped for men no longer bounding`() {
        val progress = outnumbered(400)
        val (first, second) = ids(2)
        val members = at(listOf(first, second), 60.0)
        progress.bounds(400, objective, setOf(first, second), members, listOf(defender))
        assertEquals(setOf(first, second), progress.bounds(410, objective, setOf(first, second), members, emptyList()).keys)
        assertEquals(setOf(first), progress.bounds(420, objective, setOf(first), members, listOf(defender)).keys)
        assertEquals(setOf(first), progress.bounders)
    }

    @Test fun `bounds end with the deployment`() {
        val progress = outnumbered(400)
        progress.bounds(400, objective, ids(2).toSet(), at(ids(2), 60.0), listOf(defender))
        progress.update(600, ready = 11, enemies = 0, contact = false)
        assertEquals(AttackPhase.ASSAULT, progress.phase)
        assertTrue(progress.bounds(600, objective, ids(2).toSet(), at(ids(2), 60.0), listOf(defender)).isEmpty())
        assertTrue(progress.bounders.isEmpty())
    }
}
