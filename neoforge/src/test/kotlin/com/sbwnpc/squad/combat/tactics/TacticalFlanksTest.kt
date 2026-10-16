package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalFlanksTest {
    private val squad = UUID(1, 1)
    private val focus = Vec3(80.0, 64.0, 0.0)
    private val members = (0..31).map { index ->
        TacticalMember(
            UUID(0, index.toLong()),
            Vec3((index % 8 - 3.5) * 4, 64.0, (index / 8) * 4.0),
            canFire = true,
            firingAt = focus,
            recentFire = true,
            recentFireAt = focus,
        )
    }
    private fun view() =
        TacticalSnapshot(
            0,
            SquadOrder.ATTACK,
            1,
            Vec3(0.0, 64.0, 0.0),
            focus,
            members,
            listOf(TacticalContact(UUID(2, 1), focus, 0), TacticalContact(UUID(2, 2), focus.add(0.0, 0.0, 45.0), 0)),
        )
    private fun setup(view: TacticalSnapshot = view()): Pair<SquadTacticalState, TacticalPlan> {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FOCUS_SECTOR, focus), 1, 0)
        val plan = state.plan!!
        TacticalCoordinator.assign(squad, plan, view)
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position = it.anchor }
        return state to plan
    }

    @Test fun `encirclement destinations keep their margin from the whole compact enemy group`() {
        val contacts = (0..2).map { TacticalContact(UUID(2, it.toLong()), focus.add(0.0, 0.0, it * 6.0), 0) }
        val initial = view().copy(members = members.take(8), contacts = contacts)
        val plan = TacticalPlan(1, TacticalPattern.ENCIRCLE, focus, 0, 1)
        TacticalCoordinator.assign(squad, plan, initial)
        val slots = (plan.behavior as FlankingState).order.indices
        val anchors = slots.map { TacticalFlanks.anchor(plan, initial, it) }
        val margin = CombatPositionSafety.clearance(com.sbwnpc.squad.npc.NpcClass.RIFLEMAN) + CombatPositionSafety.FLANK_MARGIN
        assertTrue(anchors.all { point -> contacts.all { point.subtract(it.position).horizontalDistance() >= margin - 0.0001 } })
        val distant = initial.copy(contacts = contacts + TacticalContact(UUID(2, 99), focus.add(1000.0, 0.0, 0.0), 0))
        assertEquals(anchors, slots.map { TacticalFlanks.anchor(plan, distant, it) }, "An unrelated distant contact must not inflate the circle")
    }

    @Test fun `a progressing final approach gets a short grace but never an unbounded extension`() {
        val initial = view().copy(members = members.take(8))
        val owner = SquadTacticalState()
        owner.select(TacticalChoice(TacticalPattern.ENCIRCLE, focus), 1, 0)
        val plan = owner.plan!!
        TacticalCoordinator.assign(squad, plan, initial)
        val deployed = initial.copy(now = 10).assumingFrontArrival(plan)
        TacticalCoordinator.advance(squad, owner, plan, deployed)
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        for (member in deployed.members) {
            val task = plan.tasks.getValue(member.id)
            if (task.job == TacticalJob.FLANK) {
                task.position = member.position.add(3.0, 0.0, 0.0)
                task.maneuverPost = true
                task.lastProgress = 410
            }
        }
        val approaching = deployed.copy(now = 421, contacts = initial.contacts.map { it.copy(seenAt = 421) })
        TacticalCoordinator.advance(squad, owner, plan, approaching)
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        assertEquals(0, (plan.behavior as FlankingState).arrived.size)
        plan.tasks.values.forEach { it.lastProgress = 490 }
        TacticalCoordinator.advance(squad, owner, plan, approaching.copy(now = 491, contacts = initial.contacts.map { it.copy(seenAt = 491) }))
        assertEquals(TacticalPhase.FAILED, plan.phase)
        assertEquals(TacticalFailure.MOVEMENT_TIMEOUT, plan.failure)
    }

    @Test fun `actual goal progress extends a long route only to its hard movement deadline`() {
        val initial = view().copy(members = members.take(8))
        val owner = SquadTacticalState()
        owner.select(TacticalChoice(TacticalPattern.ENCIRCLE, focus), 1, 0)
        val plan = owner.plan!!
        TacticalCoordinator.assign(squad, plan, initial)
        var moving = initial.copy(now = 10).assumingFrontArrival(plan)
        TacticalCoordinator.advance(squad, owner, plan, moving)
        for (member in moving.members) {
            plan.tasks[member.id]?.takeIf { it.job == TacticalJob.FLANK }?.position = member.position.add(12.0, 0.0, 0.0)
        }
        TacticalCoordinator.advance(squad, owner, plan, moving.copy(now = 20))
        for (now in listOf(421L, 600L, 821L)) {
            moving = moving.copy(
                now = now,
                contacts = initial.contacts.map { it.copy(seenAt = now) },
                members = moving.members.map { member ->
                    val task = plan.tasks.getValue(member.id)
                    if (task.job != TacticalJob.FLANK) return@map member
                    val position = TacticalLayout.towards(member.position, task.anchor, 2.0)
                    task.position = position.add(12.0, 0.0, 0.0)
                    member.copy(position = position)
                },
            )
            TacticalCoordinator.advance(squad, owner, plan, moving)
            assertEquals(if (now < 821) TacticalPhase.EXECUTING else TacticalPhase.FAILED, plan.phase)
        }
        assertEquals(TacticalFailure.MOVEMENT_TIMEOUT, plan.failure)
    }

    @Test fun `losing a waiting flanker before launch removes stale membership and preserves surviving frontage`() {
        for (pattern in listOf(TacticalPattern.FLANK, TacticalPattern.ENCIRCLE, TacticalPattern.FOCUS_SECTOR)) {
            val initial = view().copy(members = members.take(16))
            val owner = SquadTacticalState()
            owner.select(TacticalChoice(pattern, focus), 1, 0)
            val plan = owner.plan!!
            TacticalCoordinator.assign(squad, plan, initial)
            plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position = it.anchor }
            val flank = plan.behavior as FlankingState
            val dead = flank.order.first()
            val slots = initial.members.filter { it.id != dead }.associate { it.id to plan.frontage.offset(it.id) }
            val survivors = initial.copy(now = 20, members = initial.members.filter { it.id != dead }).assumingFrontArrival(plan)
            TacticalCoordinator.advance(squad, owner, plan, survivors)
            assertEquals(TacticalPhase.EXECUTING, plan.phase, pattern.toString())
            assertFalse(dead in flank.order)
            assertNull(plan.tasks[dead])
            assertFalse(dead in TacticalFlanks.pending(plan, survivors))
            assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
            slots.forEach { (id, offset) -> assertEquals(offset, plan.frontage.offset(id), "A casualty must not renumber surviving firing lanes") }
        }
    }

    @Test fun `flank destinations preserve spatial order instead of UUID order`() {
        val view = view()
        val (state, plan) = setup(view)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        val flank = plan.behavior as FlankingState
        val ordered = flank.order.sortedBy { plan.frontage.offset(it)!!.dot(plan.frontage.side) }
        val anchors = ordered.map { TacticalFlanks.anchor(plan, view, flank.order.indexOf(it)) }
        assertTrue(anchors.zipWithNext().all { (a, b) -> b.subtract(a).dot(plan.frontage.side) > 0.0 && b.distanceTo(a) >= 5.9 }, "Spatially ordered members must receive spatially ordered, separated destinations: $anchors")
    }

    @Test fun `outer flank slots leave first so inner waves do not cross waiting fighters`() {
        val view = view()
        val (state, plan) = setup(view)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        fun distance(task: TacticalTask) = kotlin.math.abs(task.anchor.subtract(focus).dot(plan.frontage.side))
        val moving = plan.tasks.values.filter { it.job == TacticalJob.FLANK }
        val waiting = plan.tasks.values.filter { it.job == TacticalJob.WAIT }
        assertEquals(6, moving.size)
        assertTrue(waiting.isNotEmpty())
        assertTrue(moving.minOf { distance(it) } >= waiting.maxOf { distance(it) })
    }

    @Test fun `wide enemy front is approached from its weaker side by no more than six movers`() {
        val view = view()
        val (state, plan) = setup(view)
        val staging = plan.tasks.values.mapNotNull { it.staging }
        assertEquals(staging.size, staging.distinct().size)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        val movers = plan.tasks.filterValues { it.job == TacticalJob.FLANK }
        assertEquals(6, movers.size)
        assertTrue(plan.tasks.values.any { it.job == TacticalJob.WAIT })
        assertTrue(movers.values.all { it.anchor.z <= -20.0 })
        assertEquals(movers.size, movers.values.map { it.anchor }.distinct().size)
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.ADVANCE })
    }

    @Test fun `available firing lanes alone cannot launch a large wave and one shooter is insufficient`() {
        val view = view().copy(members = members.map { it.copy(recentFire = false) })
        val (state, plan) = setup(view)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.PREPARING, plan.status)
        val shooter = plan.tasks.entries.first { it.value.job == TacticalJob.COVER }.key
        val one = view.copy(members = view.members.map { it.copy(recentFire = it.id == shooter) })
        TacticalCoordinator.advance(squad, state, plan, one.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.PREPARING, plan.status)
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.FLANK })
        val other = plan.tasks.entries.last { it.value.job == TacticalJob.COVER }.key
        val two = view.copy(members = view.members.map { it.copy(recentFire = it.id == shooter || it.id == other) })
        TacticalCoordinator.advance(squad, state, plan, two.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.EXECUTING, plan.status)
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
    }

    @Test fun `arrival releases the next six and preserves covering posts including distant flankers`() {
        val view = view()
        val (state, plan) = setup(view)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        val first = plan.tasks.filterValues { it.job == TacticalJob.FLANK }.keys.toSet()
        val cover = plan.tasks.filterValues { it.job == TacticalJob.COVER }.toMap()
        val arrived = view.copy(
            now = 40,
            contacts = view.contacts.map { it.copy(seenAt = 40) },
            members = view.members.map { npc ->
                if (npc.id in first) {
                    val task = plan.tasks[npc.id]!!
                    task.position = task.anchor
                    npc.copy(position = task.anchor)
                } else {
                    npc
                }
            },
        )
        TacticalCoordinator.advance(squad, state, plan, arrived.assumingFrontArrival(plan))
        assertEquals(first, (plan.behavior as FlankingState).arrived)
        assertEquals(TacticalStatus.PREPARING, plan.status)
        cover.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        first.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
        TacticalCoordinator.advance(squad, state, plan, arrived.assumingFrontArrival(plan))
        val next = plan.tasks.filterValues { it.job == TacticalJob.FLANK }.keys
        assertEquals(6, next.size)
        assertTrue(next.none { it in first })
        first.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
    }

    @Test fun `late actual shooters are promoted from waiting into the covering group`() {
        val initial = view().copy(members = members.map { it.copy(recentFire = false) })
        val (state, plan) = setup(initial)
        val shooters = plan.tasks.filterValues { it.job == TacticalJob.WAIT }.keys.toList().takeLast(2).toSet()
        val ready = initial.copy(now = 20, members = initial.members.map { it.copy(recentFire = it.id in shooters) })
        TacticalCoordinator.advance(squad, state, plan, ready.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.EXECUTING, plan.status)
        shooters.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
        val flank = plan.behavior as FlankingState
        val lateral = plan.frontage.side
        val ordered = flank.order.sortedBy { plan.frontage.offset(it)!!.dot(lateral) }
        val destinations = ordered.map { TacticalFlanks.anchor(plan, ready, flank.order.indexOf(it)).dot(lateral) }
        assertTrue(destinations.zipWithNext().all { (a, b) -> b > a }, "Support exchanges must preserve spatial order before launching a wave: $destinations")
    }

    @Test fun `one failed route does not block the remaining wave or release it into frontal movement`() {
        val view = view()
        val (state, plan) = setup(view)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        val wave = plan.tasks.filterValues { it.job == TacticalJob.FLANK }.keys.toSet()
        val failed = wave.first()
        TacticalCoordinator.abandon(plan, failed)
        val arrived = view.copy(
            now = 40,
            contacts = view.contacts.map { it.copy(seenAt = 40) },
            members = view.members.map { npc ->
                val task = plan.tasks[npc.id]
                if (task?.job == TacticalJob.FLANK) {
                    task.position = task.anchor
                    npc.copy(position = task.anchor)
                } else {
                    npc
                }
            },
        )
        TacticalCoordinator.advance(squad, state, plan, arrived.assumingFrontArrival(plan))
        assertEquals(wave - failed, (plan.behavior as FlankingState).arrived)
        assertNull(plan.tasks[failed])
        TacticalCoordinator.advance(squad, state, plan, arrived.assumingFrontArrival(plan))
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
        assertNull(plan.tasks[failed])
        state.snapshot = arrived
        assertTrue(state.holdsAfterFailure(failed, 1, 40))
    }

    @Test fun `nearby and distant maneuver proposals retain executing assignments until a new player order`() {
        val (state, plan) = setup()
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position = it.anchor }
        TacticalCoordinator.advance(squad, state, plan, view().assumingFrontArrival(plan))
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        val task = plan.tasks.values.first()
        assertFalse(state.select(TacticalChoice(TacticalPattern.BOUND, focus.add(1.0, 0.0, 0.0)), 1, 20))
        assertSame(plan, state.plan)
        assertSame(task, plan.tasks.values.first())
        assertFalse(state.select(TacticalChoice(TacticalPattern.FOCUS_SECTOR, focus.add(0.0, 0.0, 60.0)), 1, 120))
        assertSame(plan, state.plan)
        assertSame(task, plan.tasks.values.first())
        assertFalse(state.select(TacticalChoice(TacticalPattern.DISLODGE, focus.add(0.0, 0.0, 60.0)), 1, 130))
        assertSame(plan, state.plan)
        assertTrue(state.select(TacticalChoice(TacticalPattern.FOLLOW_ORDER, null), 2, 131))
        assertNotSame(plan, state.plan)
    }

    @Test fun `failed members hold in combat instead of resuming assault and fresh orders release them`() {
        val view = view()
        val (state, plan) = setup(view)
        val member = members[0].id
        state.snapshot = view
        TacticalCoordinator.abandon(plan, member)
        assertTrue(state.holdsAfterFailure(member, 1, 10))
        assertFalse(state.holdsAfterFailure(member, 2, 10))
        state.snapshot = view.copy(now = 200, contacts = emptyList())
        assertFalse(state.holdsAfterFailure(member, 1, 200))
    }

    @Test fun `failure hold cannot stop the covering work of a replacement plan`() {
        val view = view()
        val (state, old) = setup(view)
        val member = old.tasks.keys.first()
        state.snapshot = view
        state.fail(20, TacticalFailure.COVER_LOST)
        assertTrue(state.holdsAfterFailure(member, 1, 21))
        state.select(TacticalChoice(TacticalPattern.REORIENT, focus), 1, 21)
        TacticalCoordinator.assign(squad, state.plan!!, view.copy(now = 21))
        assertFalse(state.holdsAfterFailure(member, 1, 21), "a fresh local task owns movement immediately")
        TacticalCoordinator.abandon(state.plan!!, member)
        assertTrue(state.holdsAfterFailure(member, 1, 22), "unassigned members must not resume the frontal order")
        assertFalse(state.holdsAfterFailure(member, 1, 141))
    }

    @Test fun `one continuing shooter sustains a wave after two released it`() {
        val view = view()
        val (state, plan) = setup(view)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        val shooter = plan.tasks.filterValues { it.job == TacticalJob.COVER }.keys.first()
        val sustained = view.copy(
            now = 200,
            contacts = view.contacts.map { it.copy(seenAt = 200) },
            members = view.members.map {
                it.copy(canFire = it.id == shooter, recentFire = it.id == shooter)
            },
        )
        TacticalCoordinator.advance(squad, state, plan, sustained.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.EXECUTING, plan.status)
        assertEquals(200L, plan.lastCover)
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
    }

    @Test fun `lost covering fire pauses and resumes the same wave without replacing its tasks`() {
        val view = view()
        val (state, plan) = setup(view)
        TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
        val tasks = plan.tasks.toMap()
        val lost = view.copy(
            now = 90,
            contacts = view.contacts.map { it.copy(seenAt = 90) },
            members = view.members.map {
                it.copy(canFire = false, recentFire = false)
            },
        )
        TacticalCoordinator.advance(squad, state, plan, lost.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.REGROUPING, plan.status)
        assertEquals(TacticalFailure.NONE, plan.failure)
        tasks.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        assertFalse(state.select(TacticalChoice(TacticalPattern.DISLODGE, focus), 1, 100))
        val shooter = plan.tasks.filterValues { it.job == TacticalJob.COVER }.keys.first()
        val resumed = lost.copy(
            now = 130,
            contacts = view.contacts.map { it.copy(seenAt = 130) },
            members = lost.members.map {
                it.copy(canFire = it.id == shooter, recentFire = it.id == shooter)
            },
        )
        TacticalCoordinator.advance(squad, state, plan, resumed.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.EXECUTING, plan.status)
        assertEquals(40L, plan.phaseSince, "paused ticks must not consume the movement deadline")
        tasks.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
    }

    @Test fun `blocked fire releases six short probes in owned lanes and leaves the assault wave waiting`() {
        val view = view().copy(members = members.map { it.copy(canFire = false, recentFire = false) })
        val (state, plan) = setup(view)
        val old = plan.tasks.toMap()
        val deployed = view.copy(now = 80, contacts = view.contacts.map { it.copy(seenAt = 80) }).assumingFrontArrival(plan)
        TacticalCoordinator.advance(squad, state, plan, deployed)
        assertTrue((plan.phase == TacticalPhase.OPENING_LANE))
        val probes = plan.tasks.filterValues { it.opensLane }
        assertEquals(6, probes.size)
        assertFalse(plan.tasks.values.any { it.job in setOf(TacticalJob.FLANK, TacticalJob.ADVANCE) })
        for ((id, task) in probes) {
            val from = deployed.members.first { it.id == id }.position
            assertEquals(12.0, from.distanceTo(task.anchor), 0.01)
            assertTrue(task.anchor.subtract(from).dot(plan.frontage.forward) > 0.0)
            assertEquals(0.0, task.anchor.subtract(from).dot(plan.frontage.side), 0.01)
        }
        old.filterKeys { it !in probes }.forEach { (id, task) -> assertSame(task, plan.tasks[id]) }
        probes.values.forEach { it.position = it.anchor }
        val shooters = view.copy(
            now = 100,
            contacts = view.contacts.map { it.copy(seenAt = 100) },
            members = view.members.map {
                if (it.id in probes) it.copy(position = plan.tasks[it.id]!!.anchor, canFire = true, recentFire = true) else it
            },
        )
        TacticalCoordinator.advance(squad, state, plan, shooters.assumingFrontArrival(plan))
        assertFalse((plan.phase == TacticalPhase.OPENING_LANE))
        assertEquals(TacticalStatus.PREPARING, plan.status)
        probes.keys.forEach { assertEquals(TacticalJob.COVER, plan.tasks[it]?.job) }
        TacticalCoordinator.advance(squad, state, plan, shooters.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.EXECUTING, plan.status)
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
    }

    @Test fun `waiting fighters are assigned separate staging slots`() {
        val view = view()
        val (_, plan) = setup(view)
        for (member in view.members) {
            val task = plan.tasks[member.id]!!
            if (task.job == TacticalJob.WAIT) assertEquals(task.frontage!!.point, task.staging)
        }
    }

    @Test fun `unproductive lane probes stop after two attempts without releasing the assault wave`() {
        var view = view().copy(members = members.map { it.copy(canFire = false, recentFire = false) })
        val (state, plan) = setup(view)
        var now = 0L
        repeat(2) {
            now += 80
            view = view.copy(now = now, contacts = view.contacts.map { it.copy(seenAt = now) })
            TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
            assertTrue((plan.phase == TacticalPhase.OPENING_LANE))
            now += 40
            view = view.copy(
                now = now,
                contacts = view.contacts.map { it.copy(seenAt = now) },
                members = view.members.map { npc ->
                    val task = plan.tasks[npc.id]!!
                    if (task.opensLane) {
                        task.position = task.anchor
                        npc.copy(position = task.anchor)
                    } else {
                        npc
                    }
                },
            )
            TacticalCoordinator.advance(squad, state, plan, view.assumingFrontArrival(plan))
            assertFalse((plan.phase == TacticalPhase.OPENING_LANE))
        }
        assertEquals(2, (plan.behavior as CoveredManeuverState).laneAttempts)
        now += 110
        TacticalCoordinator.advance(squad, state, plan, view.copy(now = now, contacts = view.contacts.map { it.copy(seenAt = now) }).assumingFrontArrival(plan))
        assertEquals(TacticalStatus.FAILED, plan.status)
        assertEquals(TacticalFailure.COVER_NOT_READY_TIMEOUT, plan.failure)
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.FLANK })
    }

    @Test fun `a firing peek updates its endpoint while the remaining line finishes deployment`() {
        val view = view()
        val (state, plan) = setup(view)
        val cover = plan.tasks.filterValues { it.job == TacticalJob.COVER }.keys.take(2).toSet()
        val ready = view.copy(
            now = 20,
            members = view.members.map {
                if (it.id in cover) it.copy(position = plan.tasks[it.id]!!.frontage!!.point.add(0.5, 0.0, 0.0)) else it.copy(canFire = false, recentFire = false)
            },
        )
        TacticalCoordinator.advance(squad, state, plan, ready)
        assertEquals(TacticalStatus.PREPARING, plan.status)
        for (id in cover) assertEquals(ready.members.first { it.id == id }.position, plan.tasks[id]?.position)
        TacticalCoordinator.advance(squad, state, plan, ready.copy(now = 30).assumingFrontArrival(plan))
        assertEquals(TacticalStatus.PREPARING, plan.status)
        TacticalCoordinator.advance(squad, state, plan, ready.copy(now = 70).assumingFrontArrival(plan))
        assertEquals(TacticalStatus.EXECUTING, plan.status)
    }

    @Test fun `shooting while walking cannot replace the assigned cover endpoint`() {
        val view = view()
        val (state, plan) = setup(view)
        val cover = plan.tasks.filterValues { it.job == TacticalJob.COVER }.keys.toSet()
        val posts = cover.associateWith { plan.tasks[it]!!.position }
        val walking = view.copy(
            now = 20,
            members = view.members.map {
                if (it.id in cover) it.copy(position = it.position.add(0.0, 0.0, 2.0), moving = true) else it.copy(canFire = false, recentFire = false)
            },
        )
        TacticalCoordinator.advance(squad, state, plan, walking.assumingFrontArrival(plan))
        assertEquals(TacticalStatus.PREPARING, plan.status)
        posts.forEach { (id, post) -> assertEquals(post, plan.tasks[id]?.position) }
    }

    @Test fun `a distant covering fighter is still assigned instead of falling back to individual assault`() {
        val view = view().copy(
            members = members.mapIndexed { index, it ->
                if (index == 0) it.copy(position = Vec3(-70.0, 64.0, 0.0)) else it
            },
        )
        val (_, plan) = setup(view)
        assertNotNull(plan.tasks[view.members[0].id])
    }

    @Test fun `opening a firing lane cannot route forward through the enemy`() {
        val from = Vec3(0.0, 64.0, 0.0)
        assertTrue(TacticalRoutes.safeLaneProbe(from, Vec3(0.0, 64.0, 12.0), focus))
        assertFalse(TacticalRoutes.safeLaneProbe(from, Vec3(12.0, 64.0, 0.0), focus))
        assertFalse(TacticalRoutes.safeLaneProbe(from, Vec3(0.0, 64.0, 20.0), focus))
    }

    @Test fun `distant rear flank legs stay short and do not cut toward the enemy`() {
        val start = Vec3(0.0, 64.0, 0.0)
        val task = TacticalTask(TacticalJob.FLANK, focus.add(24.0, 0.0, 0.0), focus, 1)
        val leg = TacticalRoutes.leg(start, task)
        assertTrue(leg.distanceTo(start) <= 24.01)
        assertEquals(start.distanceTo(focus), leg.distanceTo(focus), 0.01)
        assertTrue(kotlin.math.abs(leg.z) > 10.0)
    }

    @Test fun `flank alternatives avoid a secondary contact when the direct leg approaches it`() {
        val from = Vec3(30.5, 64.0, 112.5)
        val focus = Vec3(130.5, 64.0, 112.5)
        val task = TacticalTask(TacticalJob.FLANK, Vec3(74.5, 64.0, 48.5), focus, 1)
        val view = view().copy(center = from, contacts = listOf(TacticalContact(UUID(2, 1), focus, 0), TacticalContact(UUID(2, 2), Vec3(60.5, 64.0, 109.5), 0)))
        val role = com.sbwnpc.squad.npc.NpcClass.RIFLEMAN
        assertFalse(TacticalClearance.permitsPosition(role, from, TacticalRoutes.leg(from, task), view))
        val probes = TacticalRoutes.flankProbes(from, task, view, role)
        assertTrue(probes.isNotEmpty())
        assertTrue(probes.any { it.distanceTo(task.anchor) < from.distanceTo(task.anchor) })
        for (point in probes) {
            assertTrue(point.distanceTo(from) <= 24.01)
            assertTrue(TacticalClearance.permitsPosition(role, from, point, view))
            assertTrue(TacticalClearance.threats(view).all { com.sbwnpc.squad.combat.CombatPositionSafety.permitsSegment(from, from, point, it, 48.0) })
        }
    }

    @Test fun `flank alternatives cannot escape a surrounding enemy ring by crossing its safety boundary`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val task = TacticalTask(TacticalJob.FLANK, Vec3(80.0, 64.0, 80.0), focus, 1)
        val contacts = listOf(Vec3(20.0, 64.0, 0.0), Vec3(-20.0, 64.0, 0.0), Vec3(0.0, 64.0, 20.0), Vec3(0.0, 64.0, -20.0))
        val view = view().copy(center = from, contacts = contacts.mapIndexed { index, point -> TacticalContact(UUID(2, index.toLong()), point, 0) })
        assertTrue(TacticalRoutes.flankProbes(from, task, view, com.sbwnpc.squad.npc.NpcClass.RIFLEMAN).isEmpty())
    }
}
