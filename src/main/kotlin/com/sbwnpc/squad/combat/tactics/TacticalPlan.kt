package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Common plan identity; maneuver-specific progress lives in [behavior]. */
class TacticalPlan(
    val id: Int, val pattern: TacticalPattern, val focus: Vec3?, val started: Long, val stamp: Int,
    initialStatus: TacticalStatus = TacticalStatus.PREPARING,
    val events: TacticalEventSink = TacticalEventSink.NONE,
    val behavior: TacticalState = TacticalStates.create(pattern),
    val reason: TacticalReason = TacticalReason.UNSPECIFIED,
    val flankSide: Double = 0.0
) {
    val assignments = TacticalTaskBoard(id, events)
    val tasks get() = assignments.tasks
    val failedMembers get() = assignments.failedMembers
    val status get() = behavior.phases.current.status
    val phase get() = behavior.phases.current.id
    val phaseSince get() = behavior.phaseSince
    val bounds get() = behavior.bounds
    val lastCover get() = behavior.lastCover
    val origin get() = behavior.origin
    val pausedSince get() = (behavior.phases.current as? PausedPhase)?.pausedAt ?: Long.MIN_VALUE
    var failure = TacticalFailure.NONE
        private set
    private var context: TacticalContext? = null
    private var entered = false
    private var exited = false

    init {
        check(!behavior.attached) { "A tactical state instance cannot be shared by plans" }
        behavior.attached = true
        behavior.phaseSince = started
        behavior.lastCover = started
        behavior.phases.initialize(initialStatus)
    }

    internal fun assign(squad: UUID, view: TacticalSnapshot, owner: SquadTacticalState? = null) {
        if (exited || status in setOf(TacticalStatus.FAILED, TacticalStatus.COMPLETED)) return
        val next = TacticalContext(squad, this, view, owner)
        context = next
        if (!entered) {
            entered = true
            events.emit(TacticalEvent.Lifecycle(id, view.now, "state_enter", pattern, phase, "plan_selected"))
            behavior.enter(next)
        } else behavior.assign(next)
    }

    internal fun tick(squad: UUID, owner: SquadTacticalState, view: TacticalSnapshot) {
        if (exited || status == TacticalStatus.FAILED || status == TacticalStatus.COMPLETED) return
        if (!entered) assign(squad, view, owner)
        if (exited || status in setOf(TacticalStatus.FAILED, TacticalStatus.COMPLETED)) return
        val next = TacticalContext(squad, this, view, owner)
        context = next
        behavior.refreshSectors(next)
        behavior.tick(next)
    }

    internal fun refreshSectors(view: TacticalSnapshot) {
        if (exited) return
        context?.copy(view = view)?.let { behavior.refreshSectors(it) }
    }

    fun complete(now: Long, trigger: String) {
        if (status in setOf(TacticalStatus.COMPLETED, TacticalStatus.FAILED)) return
        transitionTerminal(now, CompletedPhase(), trigger)
    }

    internal fun fail(now: Long, reason: TacticalFailure) {
        if (status in setOf(TacticalStatus.COMPLETED, TacticalStatus.FAILED)) return
        failure = reason
        transitionTerminal(now, FailedPhase(), reason.name)
        assignments.clear(now, reason.name)
    }

    internal fun exit(now: Long, trigger: String) {
        if (exited) return
        exited = true
        context?.copy(view = context!!.view.copy(now = now))?.let {
            if (entered) {
                behavior.exit(it, trigger)
                events.emit(TacticalEvent.Lifecycle(id, now, "state_exit", pattern, phase, trigger))
            }
        }
        assignments.clear(now, trigger)
    }

    private fun transitionTerminal(now: Long, next: TacticalPhaseState, trigger: String) {
        if (exited) return
        val previous = context
        if (previous != null) behavior.phases.transition(next, previous.copy(view = previous.view.copy(now = now)), trigger)
        else behavior.phases.initialize(next.status)
    }
}
