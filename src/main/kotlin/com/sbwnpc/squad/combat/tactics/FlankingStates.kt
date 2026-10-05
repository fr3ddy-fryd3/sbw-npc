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
    private val waitingPosts = HashMap<UUID, net.minecraft.world.phys.Vec3>()
    private var wave = emptySet<UUID>()
    internal open fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int) = TacticalFlanks.shoulder(plan, view, slot)

    override fun prepareAssignments(layout: TacticalLayout) {
        support.retainAll(layout.fighters.map { it.id }.toSet())
        if (support.isEmpty()) support.addAll(layout.support)
        if (order.isEmpty()) order.addAll(layout.fighters.filter { it.id !in support && it.id != layout.guard }.map { it.id })
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
        if (bounds != 0) return
        val cover = context.view.members.count { context.plan.tasks[it.id]?.job == TacticalJob.COVER &&
            TacticalEvidence.firesCover(it, context.plan.tasks[it.id]) }
        if (cover >= TacticalFlanks.requiredCover(context.view) || context.view.members.none {
            context.plan.tasks[it.id]?.job == TacticalJob.WAIT && TacticalEvidence.firesCover(it, context.plan.tasks[it.id])
        }) return
        support.clear()
        order.clear()
        assign(context, trigger = "late_cover_shooter")
        for (member in context.view.members) {
            val task = context.plan.tasks[member.id] ?: continue
            if (task.job == TacticalJob.COVER && TacticalEvidence.covers(member, task)) task.position = member.position
        }
    }

    override fun probeFinished(member: UUID) { support.add(member); order.remove(member) }
    override fun noMovers(context: TacticalContext) = arrived(context, emptyList())

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        arrivedMembers.addAll(movers.map { it.id })
        for (member in movers) context.tasks.put(member.id,
            TacticalTask(TacticalJob.COVER, member.position, context.plan.focus, context.plan.id).also { it.position = member.position },
            context.view.now, "flank_member_arrived")
        bounds++
        val done = TacticalFlanks.pending(context.plan, context.view).isEmpty()
        phases.transition(if (done) CompletedPhase() else PreparingPhase(), context, if (done) "flank_complete" else "flank_wave_arrived")
    }

    override fun phaseEntered(context: TacticalContext, from: TacticalPhase?) {
        if (from == TacticalPhase.EXECUTING && context.plan.phase in setOf(TacticalPhase.PREPARING, TacticalPhase.COMPLETED))
            assign(context, retainCover = true, trigger = "flank_wave_finished")
        else super.phaseEntered(context, from)
    }
}

class FlankState : FlankingState() { override val alternatesSideAfterFailure = true }
class EncircleState : FlankingState() {
    override val positions = EncirclePositions
    override val firingLaneExtension = 24.0
    override fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int) = TacticalFlanks.encircle(plan, view, slot)
}
class DislodgeState : FlankingState() { override val alternatesSideAfterFailure = true }
class FocusSectorState : FlankingState() {
    override fun anchor(plan: TacticalPlan, view: TacticalSnapshot, slot: Int) = TacticalFlanks.shoulder(plan, view, slot, weakSector = true)
}
