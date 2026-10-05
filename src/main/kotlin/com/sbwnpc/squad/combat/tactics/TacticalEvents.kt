package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Structured domain events. World logging and navigation cleanup are subscribers. */
sealed interface TacticalEvent {
    val plan: Int
    val tick: Long

    data class PlanSelected(
        override val plan: Int, override val tick: Long, val choice: TacticalChoice,
        val previous: Int?, val previousPhase: TacticalPhase?, val orderStamp: Int, val trigger: String
    ) : TacticalEvent

    data class Lifecycle(
        override val plan: Int, override val tick: Long, val action: String,
        val pattern: TacticalPattern, val phase: TacticalPhase, val trigger: String
    ) : TacticalEvent

    data class Transition(
        override val plan: Int, override val tick: Long, val from: TacticalPhase,
        val to: TacticalPhase, val trigger: String, val evidence: TacticalTransitionEvidence
    ) : TacticalEvent

    data class SelectionHeld(
        override val plan: Int, override val tick: Long, val proposed: TacticalPattern,
        val active: TacticalPattern, val reason: TacticalSelectionHold
    ) : TacticalEvent

    data class TaskAssigned(
        override val plan: Int, override val tick: Long, val member: UUID,
        val job: TacticalJob, val anchor: Vec3, val trigger: String
    ) : TacticalEvent

    data class TaskReleased(
        override val plan: Int, override val tick: Long, val member: UUID,
        val task: TacticalTask, val trigger: String
    ) : TacticalEvent

    data class TaskPaused(
        override val plan: Int, override val tick: Long, val member: UUID,
        val paused: Boolean, val reason: String
    ) : TacticalEvent

    data class MovementBlocked(
        override val plan: Int, override val tick: Long, val member: UUID,
        val previous: TacticalMovementBlocker?, val current: TacticalMovementBlocker?
    ) : TacticalEvent
}

enum class TacticalMovementBlocker { VEHICLE, ROLE_TASK, SUPPLY, DUG_IN, GRENADE, COVER, MEDIC, RETREAT }

data class TacticalTransitionEvidence(
    val members: Int, val visible: Int, val coverAssigned: Int,
    val coverReady: Int, val coverFired: Int, val movers: Int, val settledMovers: Int
)

fun interface TacticalEventSink {
    fun emit(event: TacticalEvent)
    companion object { val NONE = TacticalEventSink {} }
}

/** A direct runtime subscriber handles cleanup synchronously; optional observers collect traces. */
class TacticalEventStream(private val observer: TacticalEventSink) : TacticalEventSink {
    var runtime: TacticalEventSink = TacticalEventSink.NONE
    override fun emit(event: TacticalEvent) { runtime.emit(event); observer.emit(event) }
}

/** Optional trace collection; lifecycle side effects never depend on this buffer. */
class TacticalEventBuffer(private val capacity: Int = 256) : TacticalEventSink {
    init { require(capacity > 0) }
    private val events = ArrayDeque<TacticalEvent>()
    private var dropped = 0

    override fun emit(event: TacticalEvent) {
        if (events.size == capacity) { events.removeFirst(); dropped++ }
        events.addLast(event)
    }

    data class Batch(val events: List<TacticalEvent>, val dropped: Int)
    fun drain(): Batch = Batch(events.toList(), dropped).also { events.clear(); dropped = 0 }
}
