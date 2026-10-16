package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Retry an unchanged failed local route after a short delay; changed intent retries immediately. */
internal class CombatPathRetry {
    data class Request(val from: Vec3, val goal: Vec3, val target: UUID, val threat: Vec3)
    private var failure: Request? = null
    private var retryAt = Long.MIN_VALUE

    fun permits(request: Request, now: Long): Boolean {
        val old = failure ?: return true
        return now >= retryAt || old.target != request.target || old.from.distanceToSqr(request.from) > 0.25 ||
            old.goal.distanceToSqr(request.goal) > 0.01 || old.threat.distanceToSqr(request.threat) > 1.0
    }

    fun reject(request: Request, now: Long) {
        failure = request
        retryAt = now + 10
    }

    fun reset() {
        failure = null
    }
}
