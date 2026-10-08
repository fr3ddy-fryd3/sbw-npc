package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalStabilityRegressionTest {
    private val origin = Vec3(0.0, 64.0, 0.0)
    private val enemy = Vec3(80.0, 64.0, 0.0)
    private val squad = UUID(0, 99)
    private val members = (0L..15L).map { TacticalMember(UUID(0, it), origin.add(0.0, 0.0, it.toDouble()), canFire = true, firingAt = enemy) }
    private fun view(now: Long = 0) = TacticalSnapshot(now, SquadOrder.ATTACK, 1, origin, enemy, members, listOf(TacticalContact(UUID(1, 1), enemy, now)))

    @Test fun `suppression around half the squad cannot alternate withdrawal and reorientation`() {
        val state = SquadTacticalState()
        fun battle(now: Long, suppressed: Int) =
            view(now).copy(
                order = SquadOrder.DEFEND,
                members = members.take(8).mapIndexed { index, member -> member.copy(suppressed = index < suppressed) },
                peakStrength = 8,
                contacts = listOf(TacticalContact(UUID(1, 1), enemy, now), TacticalContact(UUID(1, 2), origin.add(-80.0, 0.0, 0.0), now)),
            )
        state.assess(squad, battle(0, 4))
        val withdrawal = state.plan!!
        assertEquals(TacticalPattern.BREAK_CONTACT, withdrawal.pattern)
        for (now in 10L..100L step 10) {
            state.assess(squad, battle(now, if (now % 20L == 0L) 4 else 3))
            assertSame(withdrawal, state.plan, "Suppression jitter must preserve assignments")
        }
        for (now in 110L..140L step 10) {
            state.assess(squad, battle(now, 1))
            assertSame(withdrawal, state.plan, "Recovery needs sustained evidence")
        }
        state.assess(squad, battle(150, 1))
        assertEquals(TacticalPattern.REORIENT, state.plan!!.pattern)
    }

    @Test fun `a one frame angle change cannot cancel a committed flank but sustained rear threats can`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
        TacticalCoordinator.advance(squad, state, state.plan!!, view())
        val plan = state.plan!!
        val tasks = plan.tasks.toMap()
        val rear = TacticalChoice(TacticalPattern.REORIENT, enemy, emergency = true, reason = TacticalReason.MULTIPLE_FRONTS)
        assertFalse(state.select(rear, 1, 10))
        assertSame(plan, state.plan)
        tasks.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        assertTrue(events.filterIsInstance<TacticalEvent.SelectionHeld>().any { it.reason == TacticalSelectionHold.THREAT_CONFIRMATION })
        for (now in listOf(20L, 30L, 40L)) assertFalse(state.select(rear, 1, now))
        assertTrue(state.select(rear, 1, 50))
        assertEquals(TacticalPattern.REORIENT, state.plan!!.pattern)
    }

    @Test fun `grenades suppression break contact and player orders still interrupt immediately`() {
        val dangers = listOf(
            TacticalChoice(TacticalPattern.EVADE, enemy, emergency = true, reason = TacticalReason.GRENADE_DANGER),
            TacticalChoice(TacticalPattern.BREAK_CONTACT, enemy, emergency = true, reason = TacticalReason.MULTIPLE_FRONTS),
            TacticalChoice(TacticalPattern.REPEL, enemy, emergency = true, reason = TacticalReason.CLOSE_OR_APPROACHING_ENEMY),
        )
        for (danger in dangers) {
            val state = SquadTacticalState()
            state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
            assertTrue(state.select(danger, 1, 10), danger.reason.toString())
        }
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
        assertTrue(state.select(TacticalChoice(TacticalPattern.REORIENT, enemy, true, TacticalReason.MULTIPLE_FRONTS), 2, 10))
    }

    @Test fun `a flanking group's movement past an enemy does not invent a rear attack`() {
        val contacts = listOf(
            TacticalContact(UUID(1, 1), Vec3(80.0, 64.0, 30.0), 0),
            TacticalContact(UUID(1, 2), Vec3(80.0, 64.0, -30.0), 0),
        )
        val stretched = view().copy(center = Vec3(100.0, 64.0, 0.0), home = Vec3(160.0, 64.0, 0.0), contacts = contacts)
        assertTrue(TacticalSituation(stretched).attackedFromMultipleFronts())
        assertFalse(TacticalSituation(stretched.copy(threatOrigin = origin)).attackedFromMultipleFronts())
        val rear = TacticalContact(UUID(1, 3), Vec3(-40.0, 64.0, 0.0), 0)
        assertTrue(TacticalSituation(stretched.copy(threatOrigin = origin, contacts = contacts + rear)).attackedFromMultipleFronts())
    }

    @Test fun `brief role preemption does not reorganize and recovery must remain stable`() {
        val episode = TacticalReorganization()
        val low = view().copy(members = members.mapIndexed { index, it -> it.copy(ready = index < 7) })
        assertFalse(episode.observe(low))
        assertFalse(episode.observe(view(20)))
        assertFalse(episode.observe(low.copy(now = 30)))
        assertTrue(episode.observe(low.copy(now = 70, contacts = view(70).contacts)))
        assertTrue(episode.observe(view(80)))
        assertFalse(episode.observe(view(180)))
        assertNull(episode.origin)
    }

    @Test fun `recovering only half the capability cannot oscillate regrouping on and off`() {
        val episode = TacticalReorganization()
        val low = view().copy(members = members.take(7))
        assertTrue(episode.observe(low))
        val halfReady = view(100).copy(members = members.mapIndexed { index, it -> it.copy(ready = index < 8) })
        assertTrue(episode.observe(halfReady))
        assertTrue(episode.observe(halfReady.copy(now = 1000)))
        assertEquals(origin, episode.origin)
        assertTrue(episode.observe(view(1010)))
        assertTrue(episode.observe(halfReady.copy(now = 1050)))
        assertTrue(episode.observe(view(1100)))
        assertFalse(episode.observe(view(1200)))
    }

    @Test fun `a depleted squad with no battle contacts continues its order`() {
        val state = SquadTacticalState()
        val low = view().copy(members = members.take(7), peakStrength = 16, contacts = emptyList())
        state.assess(squad, low)
        assertEquals(TacticalPattern.FOLLOW_ORDER, state.plan!!.pattern)
        assertEquals(false, state.snapshot!!.regrouping)
        state.assess(squad, low.copy(now = 20, contacts = view(20).contacts))
        assertEquals(TacticalPattern.REORGANIZE, state.plan!!.pattern)
    }

    @Test fun `a grenade or cooldown interruption cannot move the regrouping origin`() {
        val state = SquadTacticalState()
        val low = view().copy(members = members.take(7), peakStrength = 16)
        state.assess(squad, low)
        assertEquals(TacticalPattern.REORGANIZE, state.plan!!.pattern)
        assertEquals(origin, state.plan!!.origin)
        val moved = low.copy(now = 10, center = origin.add(-20.0, 0.0, 0.0), grenade = true)
        state.assess(squad, moved)
        assertEquals(TacticalPattern.EVADE, state.plan!!.pattern)
        state.assess(squad, moved.copy(now = 20, grenade = false))
        assertEquals(TacticalPattern.REORGANIZE, state.plan!!.pattern)
        assertEquals(origin, state.plan!!.origin)
        state.fail(30)
        state.assess(squad, moved.copy(now = 40, grenade = false))
        assertEquals(TacticalPattern.REORIENT, state.plan!!.pattern)
        state.assess(squad, moved.copy(now = 160, grenade = false))
        assertEquals(TacticalPattern.REORGANIZE, state.plan!!.pattern)
        assertEquals(origin, state.plan!!.origin)
        val same = state.plan
        state.assess(squad, moved.copy(now = 1600, grenade = false))
        assertSame(same, state.plan)
        state.assess(squad, moved.copy(now = 1610, stamp = 2, grenade = false))
        assertEquals(moved.center, state.plan!!.origin)
    }

    @Test fun `regrouping movers that cannot finish hold instead of failing and restarting the squad`() {
        val state = SquadTacticalState()
        val low = view().copy(members = members.take(7), peakStrength = 16)
        state.assess(squad, low)
        state.assess(squad, low.copy(now = 40))
        val plan = state.plan!!
        assertEquals(TacticalPhase.WITHDRAWING, plan.phase)
        val movers = plan.tasks.filterValues { it.job == TacticalJob.RETREAT }.keys
        assertTrue(movers.isNotEmpty())
        state.assess(squad, low.copy(now = 210, contacts = low.contacts.map { it.copy(seenAt = 210) }))
        assertSame(plan, state.plan)
        assertEquals(TacticalFailure.NONE, plan.failure)
        assertEquals("HOLDING", plan.phase.name)
        movers.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
        state.assess(squad, low.copy(now = 220, contacts = low.contacts.map { it.copy(seenAt = 220) }))
        movers.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
    }

    @Test fun `survivors finish regrouping without requiring dead members to return`() {
        val state = SquadTacticalState()
        fun battle(now: Long) = view(now).copy(members = members.take(7), peakStrength = 16)
        state.assess(squad, battle(0))
        state.assess(squad, battle(40))
        state.assess(squad, battle(210))
        assertEquals("HOLDING", state.plan!!.phase.name)
        val survivors = battle(220).copy(members = members.take(3).mapIndexed { index, it -> it.copy(ready = index < 2) })
        state.assess(squad, survivors)
        state.assess(squad, survivors.copy(now = 320, contacts = view(320).contacts))
        assertFalse(state.snapshot!!.regrouping!!)
        assertNotEquals(TacticalPattern.REORGANIZE, state.plan!!.pattern)
        state.assess(squad, survivors.copy(now = 500, contacts = view(500).contacts))
        assertFalse(state.snapshot!!.regrouping!!, "Historical peak must not immediately start another withdrawal")
    }

    @Test fun `empty survivors settle instead of withdrawing forever and wait for ammunition`() {
        val state = SquadTacticalState()
        val empty = view().copy(members = members.take(3).map { it.copy(ready = false, canFire = false) }, peakStrength = 7)
        state.assess(squad, empty)
        state.assess(squad, empty.copy(now = 40, contacts = view(40).contacts))
        state.assess(squad, empty.copy(now = 80, contacts = view(80).contacts))
        state.assess(squad, empty.copy(now = 90, contacts = view(90).contacts))
        assertEquals("HOLDING", state.plan!!.phase.name)
        state.assess(squad, empty.copy(now = 1000, contacts = view(1000).contacts))
        assertTrue(state.snapshot!!.regrouping!!)
        assertEquals("HOLDING", state.plan!!.phase.name)
        assertTrue(state.plan!!.tasks.values.all { it.job == TacticalJob.RESERVE })
        state.assess(squad, empty.copy(now = 1010, grenade = true, contacts = view(1010).contacts))
        assertEquals(TacticalPattern.EVADE, state.plan!!.pattern)
        state.assess(squad, empty.copy(now = 1020, contacts = view(1020).contacts))
        assertEquals("HOLDING", state.plan!!.phase.name, "Grenade evasion must not start another withdrawal")
    }

    @Test fun `an unreachable regrouping member cannot resume the attack when contacts disappear`() {
        val state = SquadTacticalState()
        val low = view().copy(members = members.take(7), peakStrength = 16)
        state.assess(squad, low)
        val plan = state.plan!!
        val member = members.first().id
        state.holdAfterFailure[member] = 120
        TacticalCoordinator.abandon(plan, member, 0, "unreachable")
        state.assess(squad, low.copy(now = 200, contacts = emptyList()))
        assertSame(plan, state.plan)
        assertTrue(state.holdsAfterFailure(member, 1, 200))
        assertFalse(state.holdsAfterFailure(member, 2, 200))
    }
}
