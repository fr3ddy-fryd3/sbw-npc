package com.sbwnpc.squad.combat.tactics

import java.util.UUID

/** A target change only cancels a post after its firing lane has actually become unusable. */
object TacticalPostReview {
    enum class Result { UNCHANGED, DEFERRED, RETAINED, INVALIDATED }

    /** A null validation result means the world-query budget is exhausted, not that the post failed. */
    fun review(task: TacticalTask, target: UUID?, now: Long, usable: () -> Boolean?): Result {
        if (task.job !in TacticalJob.FIRING_POSITIONS || task.position == null || task.deployingFront(task.position!!) || target == task.firingTarget) {
            return Result.UNCHANGED
        }
        if (target != null) {
            when (usable()) {
                null -> return Result.DEFERRED
                false -> return Result.INVALIDATED
                true -> Unit
            }
        }
        // Keep the owned path and progress timers; discard only the obsolete background search.
        task.firingTarget = target
        task.search = null
        task.nextValidation = maxOf(task.nextValidation, now + VALIDATION_INTERVAL_TICKS)
        return Result.RETAINED
    }

    private const val VALIDATION_INTERVAL_TICKS = 40L
}
