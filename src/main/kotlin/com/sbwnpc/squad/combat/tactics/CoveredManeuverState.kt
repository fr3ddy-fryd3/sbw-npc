package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import java.util.UUID

/** Common covered-movement protocol; each maneuver supplies its own assignments and arrival. */
abstract class CoveredManeuverState : TacticalState() {
    open val withdrawal = false
    open val actualCoverRequired = false
    open val canProbe = false
    open val movementTimeout = 160L
    var laneAttempts = 0
        internal set
    private val deployment = TacticalDeployment()
    private var preparationHold: TacticalPreparationHold? = null

    override fun extendsPreparation(now: Long): Boolean = phases.current.id == TacticalPhase.PREPARING && deployment.holdingPreparation(now)

    override fun beforeTick(context: TacticalContext) {
        if (context.plan.phase == TacticalPhase.PREPARING) promoteLateSupport(context)
        if (context.plan.phase == TacticalPhase.PREPARING) deployment.observe(context.plan, context.view)
        if (context.plan.phase != TacticalPhase.OPENING_LANE) {
            for (member in context.view.members) {
                val task = context.plan.tasks[member.id] ?: continue
                if (task.job in TacticalJob.FIRE_SUPPORT && TacticalEvidence.firesCover(member, task) &&
                    !member.moving && !task.deployingFront(member.position)
                ) {
                    task.position = member.position
                }
            }
        }
    }

    protected open fun promoteLateSupport(context: TacticalContext) {}
    internal open fun probeFinished(member: UUID) {}
    protected open fun readyWithoutCover(context: TacticalContext): Boolean = false

    internal fun maintainsCover(context: TacticalContext, member: TacticalMember): Boolean =
        if (actualCoverRequired) {
            TacticalEvidence.firesCover(member, context.plan.tasks[member.id])
        } else {
            TacticalEvidence.covers(member, context.plan.tasks[member.id])
        }

    internal override fun prepare(context: TacticalContext) {
        // Covering fire does not mean that the rest of the attack line has deployed.
        // Keep their current assignments until deployment finishes or its progress expires.
        val hold = deployment.holdReason(context.view.now)
        if (hold != preparationHold) {
            preparationHold = hold
            context.plan.events.emit(TacticalEvent.PreparationHeld(context.plan.id, context.view.now, hold))
        }
        if (hold != null) return
        val cover = context.view.members.filter { context.plan.tasks[it.id]?.job in TacticalJob.FIRE_SUPPORT }
        if (coverReady(context, cover) || readyWithoutCover(context)) {
            phases.transition(if (withdrawal) WithdrawingPhase() else ExecutingPhase(), context, "cover_ready")
            return
        }
        if (shouldOpenLane(context, cover) && openLane(context)) return
        if (context.view.now - phaseSince > COVER_READY_TIMEOUT) {
            context.fail(TacticalFailure.COVER_NOT_READY_TIMEOUT)
        }
    }

    private fun coverReady(context: TacticalContext, cover: List<TacticalMember>): Boolean {
        fun ready(member: TacticalMember): Boolean = maintainsCover(context, member) && TacticalEvidence.settled(member, context.plan.tasks[member.id])
        return if (actualCoverRequired) {
            cover.count { ready(it) } >= TacticalFlanks.requiredCover(context.view)
        } else {
            cover.any { ready(it) }
        }
    }

    private fun shouldOpenLane(context: TacticalContext, cover: List<TacticalMember>): Boolean =
        context.view.now - phaseSince >= COVER_PROBE_DELAY && canProbe &&
            cover.none { TacticalEvidence.firesCover(it, context.plan.tasks[it.id]) }

    internal override fun execute(context: TacticalContext) {
        val movers = context.view.members.filter { context.plan.tasks[it.id]?.job in setOf(TacticalJob.FLANK, TacticalJob.ADVANCE, TacticalJob.RETREAT) }
        if (movers.isEmpty()) {
            noMovers(context)
            return
        }
        val cover = context.view.members.filter { context.plan.tasks[it.id]?.job in TacticalJob.FIRE_SUPPORT }
        if (cover.any { maintainsCover(context, it) }) lastCover = context.view.now
        if (!withdrawal && context.view.now - lastCover > COVER_LOST_DELAY && context.view.visible.isNotEmpty()) {
            phases.transition(PausedPhase(phaseSince), context, "cover_lost_pause")
            return
        }
        if (movers.all { TacticalEvidence.settled(it, context.plan.tasks[it.id]) }) {
            arrived(context, movers)
        } else if (context.view.now - phaseSince > movementTimeout) {
            movementTimedOut(context, movers)
        }
    }

    protected open fun movementTimedOut(context: TacticalContext, movers: List<TacticalMember>) {
        context.fail(TacticalFailure.MOVEMENT_TIMEOUT)
    }

    protected open fun noMovers(context: TacticalContext) {}
    protected abstract fun arrived(context: TacticalContext, movers: List<TacticalMember>)

    internal override fun phaseEntered(context: TacticalContext, from: TacticalPhase?) {
        if (from == TacticalPhase.PREPARING && context.plan.phase in setOf(TacticalPhase.EXECUTING, TacticalPhase.WITHDRAWING)) {
            assign(context, retainCover = true, trigger = "movement_started")
        } else if (from in setOf(TacticalPhase.EXECUTING, TacticalPhase.WITHDRAWING) && context.plan.phase == TacticalPhase.PREPARING) {
            assign(context, trigger = "next_movement_group")
        }
    }

    private fun openLane(context: TacticalContext): Boolean {
        if (laneAttempts >= MAX_LANE_ATTEMPTS || context.plan.focus == null) return false
        val scouts = context.view.fighting.filter {
            it.role != NpcClass.MEDIC && !it.suppressed && it.id != medicGuard &&
                context.plan.tasks[it.id]?.job in setOf(TacticalJob.COVER, TacticalJob.WAIT)
        }.sortedWith(
            compareBy<TacticalMember> { if (context.plan.tasks[it.id]?.job == TacticalJob.COVER) 0 else 1 }
                .thenBy { it.position.distanceToSqr(context.plan.focus) },
        ).take(2)
        if (scouts.isEmpty()) return false
        phases.transition(OpeningLanePhase(scouts.map { it.id }), context, "cover_lane_blocked")
        return true
    }

    private companion object {
        const val COVER_PROBE_DELAY = 80
        const val COVER_READY_TIMEOUT = 100
        const val COVER_LOST_DELAY = 80
        const val MAX_LANE_ATTEMPTS = 2
    }
}
