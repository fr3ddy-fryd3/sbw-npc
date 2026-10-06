package com.sbwnpc.squad.combat.tactics

import java.util.UUID

/** Preparation can wait for actual deployment progress, but never for an immobile line forever. */
class TacticalDeployment {
    private val closest = HashMap<UUID, Double>()
    private var started: Long? = null
    private var lastProgress = Long.MIN_VALUE
    private var active = false

    fun observe(plan: TacticalPlan, view: TacticalSnapshot) {
        if (started != plan.phaseSince) {
            started = plan.phaseSince
            lastProgress = plan.phaseSince
            closest.clear()
        }
        val deploying = view.members.mapNotNull { member ->
            val task = plan.tasks[member.id] ?: return@mapNotNull null
            val post = task.frontage ?: return@mapNotNull null
            if (!task.deployingFront(member.position)) return@mapNotNull null
            member.id to member.position.subtract(post.point).horizontalDistance()
        }
        active = deploying.isNotEmpty()
        if (!active) return
        for ((id, distance) in deploying) {
            val previous = closest[id]
            if (previous == null) {
                closest[id] = distance
            } else if (distance < previous - PROGRESS_DISTANCE) {
                closest[id] = distance
                lastProgress = view.now
            }
        }
    }

    fun progressing(now: Long): Boolean =
        active && started?.let {
            now - it < MAXIMUM_DURATION && now - lastProgress < STALL_DURATION
        } == true

    companion object {
        const val STALL_DURATION = 100L
        const val MAXIMUM_DURATION = 600L
        private const val PROGRESS_DISTANCE = 0.5
    }
}
