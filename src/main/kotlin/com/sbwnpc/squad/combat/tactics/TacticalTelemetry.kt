package com.sbwnpc.squad.combat.tactics

/** Diagnostics distinguish an available firing lane from shots actually fired in that sector. */
object TacticalTelemetry {
    data class Front(val assigned: Int, val onLine: Int, val deploying: Int, val lateralSpan: Double, val depthSpan: Double, val adjusted: Int = 0)

    fun front(plan: TacticalPlan, view: TacticalSnapshot): Front {
        val posts = view.members.mapNotNull { member -> plan.tasks[member.id]?.frontage?.let { member to it } }
        val direction = posts.firstOrNull()?.second?.forward ?: return Front(0, 0, 0, 0.0, 0.0)
        val side = net.minecraft.world.phys.Vec3(-direction.z, 0.0, direction.x)
        val lateral = posts.map { it.first.position.dot(side) }
        val depth = posts.map { it.first.position.dot(direction) }
        val onLine = posts.count { (member, post) -> post.contains(member.position) }
        val deploying = posts.count { (member, _) -> plan.tasks[member.id]!!.deployingFront(member.position) }
        return Front(posts.size, onLine, deploying, lateral.max() - lateral.min(), depth.max() - depth.min(), posts.size - onLine - deploying)
    }

    data class Cover(val assigned: Int, val ready: Int, val settledReady: Int, val firedRecently: Int)

    fun cover(plan: TacticalPlan, view: TacticalSnapshot): Cover {
        val members = view.members.filter { plan.tasks[it.id]?.job in TacticalJob.FIRE_SUPPORT }
        return Cover(
            members.size,
            members.count { TacticalEvidence.covers(it, plan.tasks[it.id]) },
            members.count { TacticalEvidence.covers(it, plan.tasks[it.id]) && TacticalEvidence.settled(it, plan.tasks[it.id]) },
            members.count { member ->
                val focus = plan.tasks[member.id]?.focus
                val firedAt = member.recentFireAt
                member.recentFire && firedAt != null && (focus == null || firedAt.distanceTo(focus) <= 18.0)
            },
        )
    }
}
