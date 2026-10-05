package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class TacticalPhase { PREPARING, OPENING_LANE, EXECUTING, PAUSED, WITHDRAWING, COMPLETED, FAILED }

/** Phase objects own temporary phase data. Only this machine commits a transition. */
class TacticalPhaseMachine(private val owner: TacticalState) {
    var current: TacticalPhaseState = PreparingPhase()
        private set
    private var entered = false
    private var transitioning = false

    internal fun initialize(status: TacticalStatus) {
        check(!entered) { "An active phase must use a lifecycle transition" }
        current = when (status) {
            TacticalStatus.PREPARING -> PreparingPhase()
            TacticalStatus.EXECUTING -> ExecutingPhase()
            TacticalStatus.REGROUPING -> WithdrawingPhase()
            TacticalStatus.COMPLETED -> CompletedPhase()
            TacticalStatus.FAILED -> FailedPhase()
        }
    }

    internal fun enter(context: TacticalContext) {
        check(context.state === owner) { "A phase machine cannot run another plan" }
        if (entered) return
        entered = true
        current.enter(context, null)
        lifecycle(context, "phase_enter", "state_enter")
    }

    internal fun tick(context: TacticalContext) {
        check(context.state === owner && entered) { "Tick requires this plan's active lifecycle" }
        current.tick(context)
    }

    internal fun transition(next: TacticalPhaseState, context: TacticalContext, trigger: String) {
        check(context.state === owner && entered) { "Transition requires this plan's active lifecycle" }
        check(!transitioning) { "Reentrant tactical phase transition" }
        check(allowed(current.id, next.id)) { "Illegal tactical transition ${current.id} -> ${next.id}" }
        val previous = current
        val evidence = TacticalEvidence.transition(context.plan, context.view)
        transitioning = true
        try {
            previous.exit(context, next.id)
            lifecycle(context, "phase_exit", trigger)
            current = next
            context.plan.events.emit(TacticalEvent.Transition(context.plan.id, context.view.now, previous.id, next.id, trigger, evidence))
            next.enter(context, previous.id)
            context.state.phaseEntered(context, previous.id)
            lifecycle(context, "phase_enter", trigger)
        } finally { transitioning = false }
    }

    internal fun exit(context: TacticalContext, trigger: String) {
        check(context.state === owner) { "A phase machine cannot exit another plan" }
        if (!entered) return
        current.exit(context, null)
        lifecycle(context, "phase_exit", trigger)
        entered = false
    }

    private fun lifecycle(context: TacticalContext, action: String, trigger: String) =
        context.plan.events.emit(TacticalEvent.Lifecycle(context.plan.id, context.view.now, action, context.plan.pattern, current.id, trigger))

    private fun allowed(from: TacticalPhase, to: TacticalPhase): Boolean {
        if (from == TacticalPhase.COMPLETED || from == TacticalPhase.FAILED) return false
        if (to == TacticalPhase.COMPLETED || to == TacticalPhase.FAILED) return true
        return when (from) {
            TacticalPhase.PREPARING -> to in setOf(TacticalPhase.EXECUTING, TacticalPhase.WITHDRAWING, TacticalPhase.OPENING_LANE)
            TacticalPhase.OPENING_LANE -> to == TacticalPhase.PREPARING
            TacticalPhase.EXECUTING -> to in setOf(TacticalPhase.PREPARING, TacticalPhase.PAUSED, TacticalPhase.EXECUTING)
            TacticalPhase.PAUSED -> to == TacticalPhase.EXECUTING
            TacticalPhase.WITHDRAWING -> to in setOf(TacticalPhase.PREPARING, TacticalPhase.WITHDRAWING)
            TacticalPhase.COMPLETED, TacticalPhase.FAILED -> false
        }
    }
}

abstract class TacticalPhaseState(val id: TacticalPhase, val status: TacticalStatus) {
    open fun enter(context: TacticalContext, from: TacticalPhase?) {
        if (from != null) context.state.phaseSince = context.view.now
    }
    abstract fun tick(context: TacticalContext)
    open fun exit(context: TacticalContext, to: TacticalPhase?) { context.state.phaseExited(context, to) }
}

class PreparingPhase : TacticalPhaseState(TacticalPhase.PREPARING, TacticalStatus.PREPARING) {
    override fun tick(context: TacticalContext) = context.state.prepare(context)
}

open class ExecutingPhase(private val resumeSince: Long? = null) : TacticalPhaseState(TacticalPhase.EXECUTING, TacticalStatus.EXECUTING) {
    override fun enter(context: TacticalContext, from: TacticalPhase?) {
        super.enter(context, from)
        if (resumeSince != null) context.state.phaseSince = resumeSince
        if (from == TacticalPhase.PREPARING || from == TacticalPhase.PAUSED) context.state.lastCover = context.view.now
    }
    override fun tick(context: TacticalContext) = context.state.execute(context)
}

class WithdrawingPhase : TacticalPhaseState(TacticalPhase.WITHDRAWING, TacticalStatus.REGROUPING) {
    override fun enter(context: TacticalContext, from: TacticalPhase?) {
        super.enter(context, from)
        if (from == TacticalPhase.PREPARING) context.state.lastCover = context.view.now
    }
    override fun tick(context: TacticalContext) = context.state.execute(context)
}

class PausedPhase(private val activeSince: Long) : TacticalPhaseState(TacticalPhase.PAUSED, TacticalStatus.REGROUPING) {
    var pausedAt = Long.MIN_VALUE
        private set

    override fun enter(context: TacticalContext, from: TacticalPhase?) { pausedAt = context.view.now }
    override fun tick(context: TacticalContext) {
        val state = context.state as CoveredManeuverState
        val cover = context.view.members.filter { context.plan.tasks[it.id]?.job == TacticalJob.COVER }
        if (context.view.visible.isEmpty() || cover.any { state.maintainsCover(context, it) })
            state.phases.transition(ExecutingPhase(activeSince + context.view.now - pausedAt), context, "cover_restored")
        else if (context.view.now - pausedAt >= 160) context.fail(TacticalFailure.COVER_LOST)
    }
}

/** The opening probe is a real substate, not EXECUTING plus an unrelated flag. */
class OpeningLanePhase(private val members: List<UUID>) : TacticalPhaseState(TacticalPhase.OPENING_LANE, TacticalStatus.EXECUTING) {
    override fun enter(context: TacticalContext, from: TacticalPhase?) {
        super.enter(context, from)
        val state = context.state as CoveredManeuverState
        state.laneAttempts++
        val scouts = context.view.members.filter { it.id in members }
        for ((index, member) in scouts.withIndex()) {
            val toward = context.plan.focus!!.subtract(member.position).multiply(1.0, 0.0, 1.0).normalize()
                .let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }
            val side = Vec3(-toward.z, 0.0, toward.x).scale(if (index % 2 == 0) 12.0 else -12.0)
            context.tasks.put(member.id, TacticalTask(TacticalJob.SEARCH, member.position.add(side), context.plan.focus, context.plan.id)
                .also { it.opensLane = true }, context.view.now, "opening_lane_probe")
        }
    }

    override fun tick(context: TacticalContext) {
        val scouts = context.view.members.filter { it.id in members && context.plan.tasks[it.id]?.opensLane == true }
        val shooters = context.view.members.count { TacticalEvidence.firesCover(it, context.plan.tasks[it.id]) &&
            (context.plan.tasks[it.id]?.job == TacticalJob.COVER || it.id in members) }
        val trigger = when {
            shooters >= TacticalFlanks.requiredCover(context.view) -> "probe_found_cover"
            scouts.all { TacticalEvidence.settled(it, context.plan.tasks[it.id]) } -> "probe_arrived"
            context.view.now - context.state.phaseSince >= 160 -> "probe_timeout"
            else -> return
        }
        context.state.phases.transition(PreparingPhase(), context, trigger)
    }

    override fun exit(context: TacticalContext, to: TacticalPhase?) {
        if (to == TacticalPhase.PREPARING) for (member in context.view.members.filter { it.id in members && it.id !in context.plan.failedMembers }) {
            context.tasks.put(member.id, TacticalTask(TacticalJob.COVER, member.position, context.plan.focus, context.plan.id)
                .also { it.position = member.position }, context.view.now, "opening_lane_finished")
            (context.state as CoveredManeuverState).probeFinished(member.id)
        }
        super.exit(context, to)
    }
}

class CompletedPhase : TacticalPhaseState(TacticalPhase.COMPLETED, TacticalStatus.COMPLETED) {
    override fun tick(context: TacticalContext) {}
}
class FailedPhase : TacticalPhaseState(TacticalPhase.FAILED, TacticalStatus.FAILED) {
    override fun tick(context: TacticalContext) {}
}
