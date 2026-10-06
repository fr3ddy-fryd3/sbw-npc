package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs

class TacticalFrontageTest {
    private val squad = UUID(0, 99)
    private val origin = Vec3(0.0, 64.0, 0.0)
    private val enemy = Vec3(200.0, 64.0, 0.0)
    private fun view(count: Int = 32): TacticalSnapshot =
        TacticalSnapshot(
            0,
            SquadOrder.ATTACK,
            1,
            origin,
            enemy,
            (0 until count).map { TacticalMember(UUID(0, it.toLong()), origin, canFire = true, firingAt = enemy) },
            listOf(TacticalContact(UUID(1, 1), enemy, 0)),
        )

    @Test fun `six sixteen and thirty two attackers are assigned a full line of distinct slots`() {
        for (count in listOf(6, 16, 32)) {
            val state = SquadTacticalState()
            state.select(TacticalChoice(TacticalPattern.REORIENT, enemy), 1, 0)
            val plan = state.plan!!
            TacticalCoordinator.assign(squad, plan, view(count), state)
            val posts = plan.tasks.values.map { it.frontage!!.point }
            assertEquals(count, posts.size)
            assertEquals(count, posts.distinct().size)
            assertTrue(posts.all { abs(it.x) < 1.0e-8 })
            val lateral = posts.map { it.z }.sorted()
            lateral.zipWithNext().forEach { (left, right) -> assertEquals(6.0, right - left, 1.0e-8) }
            assertEquals(0.0, lateral.first() + lateral.last(), 1.0e-8)
            assertTrue(plan.tasks.values.all { it.anchor == it.frontage!!.point })
        }
    }

    @Test fun `front lanes exclude another members slot and preserve friendly spacing even with terrain rounding`() {
        val frame = TacticalFrontage().also { it.observe(view()) }
        val posts = view().members.map { frame.post(it.id, origin)!! }
        for ((index, post) in posts.withIndex()) {
            assertTrue(post.contains(post.point.add(0.5, 8.0, 0.5)))
            assertFalse(post.contains(post.point.add(3.0, 0.0, 0.0)))
            posts.filterIndexed { other, _ -> other != index }.forEach { assertFalse(post.contains(it.point)) }
        }
        val gap = SquadFormation.COMBAT_SPACING - 2.0 * TacticalFrontPost.LATERAL_TOLERANCE
        assertTrue(gap >= FiringSpots.MIN_SPACING)
    }

    @Test fun `diagonal attacks share one orientation and every local probe stays in its owners strip`() {
        val enemy = Vec3(100.0, 64.0, 100.0)
        val view = view().copy(home = enemy, contacts = listOf(TacticalContact(UUID(1, 1), enemy, 0)))
        val frame = TacticalFrontage().also { it.observe(view) }
        val posts = view.members.map { frame.post(it.id, origin)!! }
        assertEquals(1.0, frame.forward.dot(Vec3(1.0, 0.0, 1.0).normalize()), 1.0e-8)
        assertTrue(posts.all { abs(it.point.subtract(origin).dot(frame.forward)) < 1.0e-8 })
        posts.forEach { post -> assertTrue(post.probes().all { post.contains(it) }) }
    }

    @Test fun `losses reordering and transient role preemption preserve surviving slots and replacements fill gaps`() {
        val initial = view()
        val frame = TacticalFrontage().also { it.observe(initial) }
        val before = initial.members.associate { it.id to frame.post(it.id, origin) }
        val removed = initial.members[8]
        val survivorView = initial.copy(members = initial.members.filter { it.id != removed.id }.reversed().map { it.copy(ready = false) })
        frame.observe(survivorView)
        survivorView.members.forEach { assertEquals(before[it.id], frame.post(it.id, origin)) }
        val replacement = removed.copy(id = UUID(0, 100), position = before[removed.id]!!.point)
        frame.observe(survivorView.copy(members = survivorView.members + replacement))
        assertEquals(before[removed.id], frame.post(replacement.id, origin))
    }

    @Test fun `new plans and target switches keep frontage but a new player order can turn the line`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
        val first = state.plan!!
        TacticalCoordinator.assign(squad, first, view(), state)
        val wait = first.tasks.entries.first { it.value.job == TacticalJob.WAIT }
        val previous = wait.value.frontage
        val sideEnemy = Vec3(200.0, 64.0, 20.0)
        val changed = view().copy(contacts = listOf(TacticalContact(UUID(1, 2), sideEnemy, 20)))
        state.select(TacticalChoice(TacticalPattern.REORIENT, sideEnemy, true), 1, 20)
        TacticalCoordinator.assign(squad, state.plan!!, changed, state)
        assertEquals(previous, state.plan!!.tasks[wait.key]!!.frontage)
        state.select(TacticalChoice(TacticalPattern.REORIENT, sideEnemy, true), 2, 21)
        TacticalCoordinator.assign(squad, state.plan!!, changed.copy(stamp = 2, contacts = listOf(TacticalContact(UUID(1, 2), Vec3(0.0, 64.0, 200.0), 21))), state)
        assertEquals(Vec3(0.0, 0.0, 1.0), state.plan!!.tasks[wait.key]!!.frontage!!.forward)
    }

    @Test fun `confirmed reorientation can turn the whole line towards a new enemy front`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
        TacticalCoordinator.assign(squad, state.plan!!, view(), state)
        val newEnemy = Vec3(0.0, 64.0, 200.0)
        state.select(TacticalChoice(TacticalPattern.REORIENT, newEnemy, true), 1, 100)
        val turned = view().copy(contacts = listOf(TacticalContact(UUID(1, 2), newEnemy, 100)))
        TacticalCoordinator.assign(squad, state.plan!!, turned, state)
        val posts = state.plan!!.tasks.values.map { it.frontage!! }
        assertTrue(posts.all { it.forward == Vec3(0.0, 0.0, 1.0) })
        assertTrue(posts.all { abs(it.point.z) < 1.0e-8 })
        assertEquals(32, posts.map { it.point.x }.distinct().size)
    }

    @Test fun `a crowded group shooting from its spawn is not treated as deployed covering fire`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK, enemy), 1, 0)
        val plan = state.plan!!
        val initial = view()
        TacticalCoordinator.assign(squad, plan, initial, state)
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position = origin }
        TacticalCoordinator.advance(squad, state, plan, initial)
        assertEquals(TacticalPhase.PREPARING, plan.phase)
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.FLANK })
        assertEquals(0, TacticalTelemetry.front(plan, initial).onLine)
        val deployed = initial.assumingFrontArrival(plan)
        TacticalCoordinator.advance(squad, state, plan, deployed)
        assertEquals(TacticalPhase.EXECUTING, plan.phase)
        assertEquals(6, plan.tasks.values.count { it.job == TacticalJob.FLANK })
        assertEquals(plan.tasks.values.count { it.frontage != null }, TacticalTelemetry.front(plan, deployed).onLine)
    }

    @Test fun `deployment geometry proposes bounded progressing legs that do not count as settled posts`() {
        val post = TacticalFrontPost(Vec3(0.0, 64.0, 93.0), Vec3(1.0, 0.0, 0.0))
        var position = origin
        val task = TacticalTask(TacticalJob.COVER, post.point, enemy, 1).apply { frontage = post }
        repeat(4) {
            val leg = post.leg(position)
            assertTrue(leg.distanceTo(position) <= 24.0 + 1.0e-8)
            assertTrue(post.acceptsDeployment(position, leg))
            task.position = leg
            val member = view(1).members.single().copy(position = leg)
            if (!post.contains(leg)) assertFalse(TacticalEvidence.settled(member, task))
            position = leg
        }
        assertEquals(post.point, position)
        assertTrue(TacticalEvidence.settled(view(1).members.single().copy(position = position), task))
        assertFalse(post.acceptsDeployment(origin, origin.add(0.0, 0.0, -12.0)))
        assertTrue(TacticalFirePosts.recover(task, origin))
        assertEquals(post.point, task.localFirePost)
        assertEquals(TacticalPostReview.Result.UNCHANGED, TacticalPostReview.review(task.apply { this.position = origin }, UUID(1, 2), 20) { fail("A deployment leg is not a firing post") })
    }

    @Test fun `medics defence retreat and grenade evasion do not inherit assault lanes`() {
        for ((pattern, order) in listOf(
            TacticalPattern.REORIENT to SquadOrder.DEFEND,
            TacticalPattern.REORGANIZE to SquadOrder.ATTACK,
            TacticalPattern.EVADE to SquadOrder.ATTACK,
            TacticalPattern.FILE to SquadOrder.ATTACK,
        )) {
            val plan = TacticalPlan(1, pattern, enemy, 0, 1)
            TacticalCoordinator.assign(squad, plan, view().copy(order = order))
            assertTrue(plan.tasks.values.all { it.frontage == null })
        }
        val initial = view().copy(members = view().members + TacticalMember(UUID(0, 100), origin, role = NpcClass.MEDIC))
        val plan = TacticalPlan(1, TacticalPattern.REORIENT, enemy, 0, 1)
        TacticalCoordinator.assign(squad, plan, initial)
        assertEquals(TacticalJob.RESERVE, plan.tasks[UUID(0, 100)]!!.job)
        assertNull(plan.tasks[UUID(0, 100)]!!.frontage)
    }

    @Test fun `finishing deployment invalidates a paused transit search before selecting a firing post`() {
        val transit = TacticalPositionSearch(origin, listOf(origin.add(0.0, 0.0, 24.0)), deployment = true)
        assertFalse(transit.needsRestart(origin.add(0.0, 0.0, 0.5), null, deployment = true))
        assertTrue(transit.needsRestart(origin.add(0.0, 0.0, 0.5), null, deployment = false))
    }

    @Test fun `advancing half keeps unique lanes even when the measured center shifts sideways`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.BOUND, enemy), 1, 0)
        val plan = state.plan!!
        val initial = view()
        TacticalCoordinator.assign(squad, plan, initial, state)
        val columns = initial.members.associate { it.id to plan.tasks[it.id]!!.frontage!!.point.z }
        val shifted = initial.assumingFrontArrival(plan).copy(center = origin.add(0.0, 0.0, 3.0))
        TacticalCoordinator.advance(squad, state, plan, shifted)
        val moving = plan.tasks.filterValues { it.job == TacticalJob.ADVANCE }
        assertEquals(16, moving.size)
        for ((member, task) in moving) {
            assertEquals(columns[member]!!, task.anchor.z, 0.2)
            assertNotNull(task.frontage)
            assertFalse(task.frontage!!.contains(task.anchor.add(0.0, 0.0, 6.0)))
        }
    }

    @Test fun `recovery permits depth adjustment in the same lane and recognises a selected post`() {
        val post = TacticalFrontPost(origin, Vec3(1.0, 0.0, 0.0))
        val task = TacticalTask(TacticalJob.WAIT, enemy, enemy, 1).apply {
            staging = origin
            frontage = post
        }
        // The original two-block-deep strip is behind a rise. Its crest is eight blocks ahead.
        val crest = origin.add(8.0, 2.0, 0.0)
        assertTrue(task.deployingFront(crest))
        assertFalse(post.probes().any { it.x >= crest.x })
        assertTrue(TacticalFirePosts.recover(task, origin))
        assertTrue(post.probes(recovery = true).contains(origin.add(8.0, 0.0, 0.0)))
        assertTrue(task.permitsFront(crest))
        assertFalse(task.permitsFront(crest.add(0.0, 0.0, 6.0)))
        assertFalse(task.permitsFront(origin.add(13.0, 0.0, 0.0)))
        task.position = crest
        val member = view(1).members.single().copy(position = crest)
        assertTrue(TacticalEvidence.settled(member, task))
        assertEquals(TacticalPostReview.Result.RETAINED, TacticalPostReview.review(task, UUID(1, 2), 20) { true })
    }
}
