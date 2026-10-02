package com.sbwnpc.squad.squad

import com.sbwnpc.squad.npc.NpcClass.MEDIC
import com.sbwnpc.squad.npc.NpcClass.RIFLEMAN
import com.sbwnpc.squad.npc.NpcClass.SNIPER
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BarracksRecruitmentTest {
    private val interval = 600
    private fun request(squad: UUID = UUID.randomUUID(), replacement: Boolean = false) =
        BarracksRecruitmentQueue.Request(squad, UUID.randomUUID(), replacement)

    @Test
    fun `seven initial soldiers need seven full intervals including the first`() {
        val queue = BarracksRecruitmentQueue()
        val waiting = MutableList(7) { request() }
        queue.synchronize(waiting, interval)
        val released = mutableListOf<Int>()
        for (tick in 1..4200) {
            queue.tick()
            queue.synchronize(waiting, interval)
            if (queue.remainingTicks == 0) {
                val next = queue.next()!!
                queue.complete(next, interval)
                waiting.remove(next)
                released += tick
            }
        }
        assertEquals(listOf(600, 1200, 1800, 2400, 3000, 3600, 4200), released)
    }

    @Test
    fun `multiple squads share one clock and repeated synchronization creates no duplicates`() {
        val queue = BarracksRecruitmentQueue()
        val requests = listOf(request(), request(replacement = true), request())
        repeat(10) { queue.synchronize(requests + requests, interval) }
        assertEquals(3, queue.ordered().size)
        repeat(interval) { queue.tick() }
        assertTrue(queue.next()!!.replacement)
        queue.complete(queue.next()!!, interval)
        assertThrows(IllegalStateException::class.java) { queue.complete(queue.next()!!, interval) }
        assertEquals(2, queue.ordered().size)
    }

    @Test
    fun `save and reload preserve exact remaining time request identities and order`() {
        val queue = BarracksRecruitmentQueue()
        val requests = listOf(request(), request(replacement = true), request())
        queue.synchronize(requests, interval)
        repeat(137) { queue.tick() }
        val restored = BarracksRecruitmentQueue.load(queue.save())
        assertEquals(463, restored.remainingTicks)
        assertEquals(queue.ordered(), restored.ordered())
        // Time while the block is unloaded has no effect: no wall-clock or game-time deadline.
        restored.synchronize(requests, interval)
        assertEquals(463, restored.remainingTicks)
        repeat(463) { restored.tick() }
        restored.complete(restored.next()!!, interval)
        assertEquals(interval, restored.remainingTicks)
    }

    @Test
    fun `blocked production cannot accumulate a batch`() {
        val queue = BarracksRecruitmentQueue()
        val requests = listOf(request(), request(), request())
        queue.synchronize(requests, interval)
        repeat(10000) { queue.tick() }
        val restored = BarracksRecruitmentQueue.load(queue.save())
        assertEquals(0, restored.remainingTicks)
        assertEquals(requests, restored.ordered())
        restored.complete(restored.next()!!, interval)
        assertEquals(interval, restored.remainingTicks)
        assertThrows(IllegalStateException::class.java) { restored.complete(restored.next()!!, interval) }
    }

    @Test
    fun `three replacements precede an initial recruit then initial gets its turn after reload`() {
        var queue = BarracksRecruitmentQueue()
        val initial = request()
        val replacements = List(6) { request(replacement = true) }
        queue.synchronize(listOf(initial) + replacements, interval)
        assertEquals(replacements.take(3) + initial + replacements.drop(3), queue.ordered())
        repeat(3) {
            repeat(interval) { queue.tick() }
            queue.complete(queue.next()!!, interval)
        }
        queue = BarracksRecruitmentQueue.load(queue.save())
        assertEquals(initial, queue.next())
    }

    @Test
    fun `removing or moving a squad cancels its requests without restarting other production`() {
        val queue = BarracksRecruitmentQueue()
        val deleted = request()
        val survivor = request()
        queue.synchronize(listOf(deleted, survivor), interval)
        repeat(200) { queue.tick() }
        queue.synchronize(listOf(survivor), interval)
        assertEquals(listOf(survivor), queue.ordered())
        assertEquals(400, queue.remainingTicks)
    }

    @Test
    fun `entirely new orders wait a full first interval and repeated apply cannot accelerate it`() {
        val queue = BarracksRecruitmentQueue()
        queue.synchronize(listOf(request()), interval)
        repeat(interval) { queue.tick() }
        val changed = request()
        queue.synchronize(listOf(changed), interval)
        assertEquals(interval, queue.remainingTicks)
        repeat(23) { queue.tick(); queue.synchronize(listOf(changed), interval) }
        assertEquals(interval - 23, queue.remainingTicks)
    }

    @Test
    fun `unloaded occupants reserve their exact roles even when another role dies`() {
        val rifleman = UUID.randomUUID()
        val sniper = UUID.randomUUID()
        val medic = UUID.randomUUID()
        val roster = RecruitmentRoster.migrate(listOf(RIFLEMAN, SNIPER, MEDIC),
            linkedMapOf(rifleman to RIFLEMAN, sniper to SNIPER, medic to MEDIC))!!
        // No entity resolution is needed after saving: an unloaded sniper still reserves SNIPER.
        val restored = RecruitmentRoster.load(roster.save())
        restored.release(medic)
        assertEquals(listOf(MEDIC), restored.vacancies().map { it.role })
        assertEquals(sniper, restored.slots.single { it.role == SNIPER }.occupant)
        assertTrue(restored.vacancies().single().recruited)
    }

    @Test
    fun `legacy unknown roles delay migration instead of guessing a replacement class`() {
        assertNull(RecruitmentRoster.migrate(listOf(RIFLEMAN, SNIPER, MEDIC),
            mapOf(UUID.randomUUID() to RIFLEMAN, UUID.randomUUID() to null)))
    }

    @Test
    fun `legacy wiped squad preserves every replacement place`() {
        val roster = RecruitmentRoster.migrate(listOf(RIFLEMAN, SNIPER, MEDIC), emptyMap())!!
        assertEquals(3, roster.vacancies().size)
        assertTrue(roster.vacancies().all { it.recruited })
    }

    @Test
    fun `reconfiguration preserves living members and matching pending identities`() {
        val rifleman = UUID.randomUUID()
        val roster = RecruitmentRoster.migrate(listOf(RIFLEMAN, MEDIC), mapOf(rifleman to RIFLEMAN))!!
        val medicPlace = roster.vacancies().single().id
        roster.reconfigure(listOf(MEDIC, SNIPER))
        assertEquals(rifleman, roster.slots.single { it.role == RIFLEMAN }.occupant)
        assertFalse(roster.slots.single { it.role == RIFLEMAN }.active)
        assertEquals(medicPlace, roster.slots.single { it.role == MEDIC }.id)
        assertFalse(roster.slots.single { it.role == SNIPER }.recruited)
        roster.release(rifleman)
        assertEquals(listOf(MEDIC, SNIPER), roster.slots.map { it.role })
    }

    @Test
    fun `empty garrison and its vacant places survive the squad save format`() {
        val squad = Squad(UUID.randomUUID(), "Alpha", SquadFaction.DEFAULT, SquadOrder.DEFEND,
            mutableListOf(), BlockPos.ZERO, null, UUID.randomUUID(),
            BarracksRef(Level.OVERWORLD, BlockPos.ZERO), listOf(RIFLEMAN, MEDIC), NpcRank.DEFAULT)
        squad.recruitmentRoster = RecruitmentRoster.empty(squad.originalComposition)
        val loaded = Squad.load(squad.save())
        assertEquals(squad.id, loaded.id)
        assertEquals(squad.barracks, loaded.barracks)
        assertEquals(squad.recruitmentRoster!!.slots.map { it.id }, loaded.recruitmentRoster!!.slots.map { it.id })
        assertEquals(listOf(RIFLEMAN, MEDIC), loaded.recruitmentRoster!!.vacancies().map { it.role })
    }
}
