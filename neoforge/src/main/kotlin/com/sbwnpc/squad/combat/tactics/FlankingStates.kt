package com.sbwnpc.squad.combat.tactics

import java.util.Collections
import java.util.UUID

/** Common wave protocol; each concrete maneuver has its own state instance and geometry. */
abstract class FlankingState : CoveredManeuverState() {
    override val stability = TacticalStability(committed = true, phaseLimit = BASE_MOVEMENT_TICKS + MAX_PROGRESS_EXTENSION_TICKS + FINAL_APPROACH_GRACE_TICKS)
    override val assignmentRange = 160.0
    override val actualCoverRequired = true
    override val canProbe = true
    override val movementTimeout = BASE_MOVEMENT_TICKS
    override val holdsFailedMembers = true
    internal val support = HashSet<UUID>()
    internal val order = ArrayList<UUID>()
    private val arrivedMembers = HashSet<UUID>()
    val arrived: Set<UUID> = Collections.unmodifiableSet(arrivedMembers)
    override fun usesAttackFront(member: TacticalMember): Boolean = member.id !in arrived
    private val waitingPosts = HashMap<UUID, net.minecraft.world.phys.Vec3>()
    private var wave = emptySet<UUID>()
    private var nextSupportReview = Long.MIN_VALUE
    private var gracePhase = Long.MIN_VALUE
    private data class Approach(val closest: Double, val progressAge: Long? = null)
    private val approaches = HashMap<UUID, Approach>()
    internal open fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int) = TacticalFlanks.shoulder(plan, view, slot)

    override fun beforeTick(context: TacticalContext) {
        super.beforeTick(context)
        if (context.plan.phase != TacticalPhase.EXECUTING) return
        val settled = context.view.members.filter {
            context.plan.tasks[it.id]?.job == TacticalJob.FLANK && TacticalEvidence.settled(it, context.plan.tasks[it.id])
        }
        recordArrivals(context, settled)
        observeApproaches(context)
    }

    private fun observeApproaches(context: TacticalContext) {
        val age = context.view.now - phaseSince
        for (member in context.view.members) {
            val task = context.plan.tasks[member.id] ?: continue
            if (task.job != TacticalJob.FLANK) continue
            val distance = member.position.distanceTo(task.anchor)
            val previous = approaches[member.id]
            approaches[member.id] = when {
                previous == null -> Approach(distance)
                distance < previous.closest - GOAL_PROGRESS_DISTANCE -> Approach(distance, age)
                else -> previous
            }
        }
    }

    override fun prepareAssignments(layout: TacticalLayout) {
        // The frontage drops missing actors when it observes the snapshot. Match membership
        // before sorting wings so a casualty cannot leave a slot without a live position.
        val alive = layout.view.members.map { it.id }.toSet()
        order.retainAll(alive)
        arrivedMembers.retainAll(alive)
        waitingPosts.keys.retainAll(alive)
        support.retainAll(layout.fighters.map { it.id }.toSet())
        if (support.isEmpty()) support.addAll(layout.support)
        if (order.isEmpty()) {
            order.addAll(layout.fighters.filter { it.id !in support && it.id != layout.guard }.map { it.id })
        }
        // Role exchanges and completed probes change membership before the first wave.
        // Match slots again so a replacement cannot inherit the opposite wing's destination.
        val spatial = TacticalFlanks.spatialOrder(layout.plan, layout.view)
        order.clear()
        order.addAll(spatial)
        wave = TacticalFlanks.pending(layout.plan, layout.view).take(TacticalFlanks.TEAM_SIZE).toSet()
    }

    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val slot = order.indexOf(member.id)
        if (slot < 0 || member.id in arrived || member.id in support || member.id == layout.guard) return layout.cover(member)
        val job = if (layout.plan.status != TacticalStatus.PREPARING && member.id in wave) TacticalJob.FLANK else TacticalJob.WAIT
        return layout.intent(job, TacticalFlanks.anchor(layout.plan, layout.view, slot)).also {
            if (job == TacticalJob.WAIT) it.staging = waitingPosts.getOrPut(member.id) { member.position }
        }
    }

    override fun promoteLateSupport(context: TacticalContext) {
        if (bounds != 0 || context.view.now < nextSupportReview) return
        val cover = context.view.members.count {
            context.plan.tasks[it.id]?.job == TacticalJob.COVER &&
                TacticalEvidence.firesCover(it, context.plan.tasks[it.id])
        }
        if (cover >= TacticalFlanks.requiredCover(context.view)) return
        val missing = TacticalFlanks.requiredCover(context.view) - cover
        val shooters = context.view.members.filter { member ->
            member.grounded && context.plan.tasks[member.id]?.job == TacticalJob.WAIT &&
                TacticalEvidence.firesCover(member, context.plan.tasks[member.id]) &&
                !context.plan.tasks.getValue(member.id).deployingFront(member.position)
        }.take(missing)
        var exchanged = false
        for (shooter in shooters) {
            val replaced = context.view.members.firstOrNull { member ->
                member.grounded && member.id in support && context.plan.tasks[member.id]?.job == TacticalJob.COVER &&
                    !TacticalEvidence.firesCover(member, context.plan.tasks[member.id])
            } ?: break
            val slot = order.indexOf(shooter.id)
            if (slot < 0) continue
            support.remove(replaced.id)
            support.add(shooter.id)
            order[slot] = replaced.id
            val oldCover = context.plan.tasks.getValue(replaced.id)
            val oldWait = context.plan.tasks.getValue(shooter.id)
            val promoted = oldWait.withJob(TacticalJob.COVER, oldWait.staging ?: oldWait.anchor)
            if (!shooter.moving) promoted.position = shooter.position
            context.tasks.put(shooter.id, promoted, context.view.now, "late_cover_shooter")
            context.tasks.put(replaced.id, oldCover.withJob(TacticalJob.WAIT, TacticalFlanks.anchor(context.plan, context.view, slot)), context.view.now, "late_cover_replaced")
            exchanged = true
        }
        if (exchanged) nextSupportReview = context.view.now + 80
    }

    override fun probeFinished(member: UUID) {
        support.add(member)
        order.remove(member)
    }
    override fun noMovers(context: TacticalContext) = arrived(context, emptyList())

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        recordArrivals(context, movers)
        bounds++
        val done = TacticalFlanks.pending(context.plan, context.view).isEmpty()
        if (done && order.any { it in context.plan.failedMembers }) {
            context.fail(TacticalFailure.PARTIAL_MANEUVER)
            return
        }
        phases.transition(if (done) CompletedPhase() else PreparingPhase(), context, if (done) "flank_complete" else "flank_wave_arrived")
    }

    private fun recordArrivals(context: TacticalContext, movers: List<TacticalMember>) {
        arrivedMembers.addAll(movers.map { it.id })
        for (member in movers) {
            context.tasks.put(
                member.id,
                TacticalTask(TacticalJob.COVER, member.position, context.plan.focus, context.plan.id).also { it.position = member.position },
                context.view.now,
                "flank_member_arrived",
            )
        }
    }

    override fun movementTimedOut(context: TacticalContext, movers: List<TacticalMember>) {
        val finishingPosts = movers.isNotEmpty() && movers.all { member ->
            val task = context.plan.tasks[member.id]
            task?.position?.distanceTo(member.position)?.let { it <= FINAL_APPROACH_RADIUS } == true &&
                (task.maneuverPost || member.position.distanceTo(task.anchor) <= 10.0) &&
                context.view.now - task.lastProgress <= FINAL_APPROACH_PROGRESS_TICKS
        }
        val age = context.view.now - phaseSince
        val progressingGoals = movers.isNotEmpty() && movers.all { member ->
            approaches[member.id]?.progressAge?.let { age - it <= GOAL_PROGRESS_STALE_TICKS } == true
        }
        val shortApproach = finishingPosts && age <= movementTimeout + FINAL_APPROACH_GRACE_TICKS
        val longerRoute = progressingGoals && age <= movementTimeout + MAX_PROGRESS_EXTENSION_TICKS
        if (shortApproach || longerRoute) {
            if (gracePhase != phaseSince) {
                gracePhase = phaseSince
                context.plan.events.emit(TacticalEvent.Lifecycle(context.plan.id, context.view.now, "movement_deadline_extended", context.plan.pattern, context.plan.phase, if (longerRoute) "flank_goal_progress" else "final_post_progress"))
            }
            return
        }
        val settled = movers.filter { TacticalEvidence.settled(it, context.plan.tasks[it.id]) }
        if (settled.isEmpty() && wave.none { it in arrived }) {
            super.movementTimedOut(context, movers)
            return
        }
        for (member in movers.filter { it !in settled }) {
            context.owner?.holdAfterFailure?.set(member.id, context.view.now + 120)
            context.tasks.abandon(member.id, context.view.now, "flank_straggler_timeout")
        }
        arrived(context, settled)
    }

    override fun phaseEntered(context: TacticalContext, from: TacticalPhase?) {
        if (from == TacticalPhase.PREPARING && context.plan.phase == TacticalPhase.EXECUTING) approaches.clear()
        if (from == TacticalPhase.EXECUTING && context.plan.phase in setOf(TacticalPhase.PREPARING, TacticalPhase.COMPLETED)) {
            assign(context, retainCover = true, trigger = "flank_wave_finished")
        } else {
            super.phaseEntered(context, from)
        }
    }

    private companion object {
        const val BASE_MOVEMENT_TICKS = 400L
        const val FINAL_APPROACH_RADIUS = 8.0
        const val FINAL_APPROACH_PROGRESS_TICKS = 40L
        const val FINAL_APPROACH_GRACE_TICKS = 80L
        const val GOAL_PROGRESS_DISTANCE = 0.5
        const val GOAL_PROGRESS_STALE_TICKS = 80L
        const val MAX_PROGRESS_EXTENSION_TICKS = 400L
    }
}

class FlankState : FlankingState() {
    override val alternatesSideAfterFailure = true
}
class EncircleState : FlankingState() {
    override val positions = EncirclePositions
    override val firingLaneExtension = 24.0
    override fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int) = TacticalFlanks.encircle(plan, view, slot)
}
class DislodgeState : FlankingState() {
    override val alternatesSideAfterFailure = true
}
class FocusSectorState : FlankingState() {
    override fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int) = TacticalFlanks.shoulder(plan, view, slot, weakSector = true)
}
