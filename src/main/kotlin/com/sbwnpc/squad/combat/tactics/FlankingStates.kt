package com.sbwnpc.squad.combat.tactics

import java.util.Collections
import java.util.UUID

/** Common wave protocol; each concrete maneuver has its own state instance and geometry. */
abstract class FlankingState : CoveredManeuverState() {
    override val stability = TacticalStability(committed = true, phaseLimit = 480)
    override val assignmentRange = 160.0
    override val actualCoverRequired = true
    override val canProbe = true
    override val movementTimeout = 400L
    override val holdsFailedMembers = true
    internal val support = HashSet<UUID>()
    internal val order = ArrayList<UUID>()
    private val arrivedMembers = HashSet<UUID>()
    val arrived: Set<UUID> = Collections.unmodifiableSet(arrivedMembers)
    override fun usesAttackFront(member: TacticalMember): Boolean = member.id !in arrived
    private val waitingPosts = HashMap<UUID, net.minecraft.world.phys.Vec3>()
    private var wave = emptySet<UUID>()
    private var nextSupportReview = Long.MIN_VALUE
    internal open fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int) = TacticalFlanks.shoulder(plan, view, slot)

    override fun beforeTick(context: TacticalContext) {
        super.beforeTick(context)
        if (context.plan.phase != TacticalPhase.EXECUTING) return
        val settled = context.view.members.filter {
            context.plan.tasks[it.id]?.job == TacticalJob.FLANK && TacticalEvidence.settled(it, context.plan.tasks[it.id])
        }
        recordArrivals(context, settled)
    }

    override fun prepareAssignments(layout: TacticalLayout) {
        support.retainAll(layout.fighters.map { it.id }.toSet())
        if (support.isEmpty()) support.addAll(layout.support)
        if (order.isEmpty()) {
            order.addAll(layout.fighters.filter { it.id !in support && it.id != layout.guard }.map { it.id })
            val spatial = TacticalFlanks.spatialOrder(layout.plan, layout.view)
            order.clear()
            order.addAll(spatial)
        }
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
        if (from == TacticalPhase.EXECUTING && context.plan.phase in setOf(TacticalPhase.PREPARING, TacticalPhase.COMPLETED)) {
            assign(context, retainCover = true, trigger = "flank_wave_finished")
        } else {
            super.phaseEntered(context, from)
        }
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
