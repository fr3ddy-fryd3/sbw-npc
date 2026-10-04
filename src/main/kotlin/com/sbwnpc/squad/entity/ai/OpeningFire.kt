package com.sbwnpc.squad.entity.ai

import java.util.UUID

/** Gives a newly sighted target an opening burst before optional movement to a firing post. */
internal class OpeningFire {
    private var target: UUID? = null
    private var firstSight: Int? = null
    private var shots = 0

    fun hold(targetId: UUID, tick: Int, shootable: Boolean, automatic: Boolean): Boolean {
        if (targetId != target) {
            reset()
            target = targetId
        }
        if (!shootable) return false
        val since = firstSight ?: tick.also { firstSight = it }
        return tick - since < 100 && shots < if (automatic) 4 else 1
    }

    fun fired() { shots++ }

    fun reset() {
        target = null
        firstSight = null
        shots = 0
    }
}
