package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3

/** Opening a blocked lane has an explicit movement goal, distinct from finding a firing post. */
object TacticalLaneRecovery {
    fun forwardStep(view: TacticalSnapshot): Double = if (view.incoming.isEmpty() && view.members.none { it.suppressed }) 12.0 else 4.0

    fun probe(context: TacticalContext, member: TacticalMember): TacticalTask {
        val old = context.plan.tasks.getValue(member.id)
        val direction = context.plan.frontage.forward
        val post = old.frontage?.point ?: member.position
        val distance = forwardStep(context.view)
        val aheadOfPost = member.position.subtract(post).dot(direction).coerceAtLeast(0.0)
        val point = post.add(direction.scale(aheadOfPost + distance))
        return TacticalTask(TacticalJob.SEARCH, point, context.plan.focus, context.plan.id).also {
            it.frontage = TacticalFrontPost(point, direction)
            it.opensLane = true
            it.probeAdvance = distance
        }
    }

    /** A probe only advances the common front after physically reaching its next strip. */
    fun nextFront(context: TacticalContext, scouts: List<TacticalMember>): Vec3? {
        if (!context.view.offensive) return null
        val settled = scouts.filter { it.grounded && TacticalEvidence.settled(it, context.plan.tasks[it.id]) }
        if (settled.isEmpty()) return null
        val front = context.state.attackFrontCenter(context)
        val direction = context.plan.frontage.forward
        val progress = settled.map { it.position.subtract(front).dot(direction) }.sorted()
        val depth = progress[progress.size / 2].coerceAtMost(forwardStep(context.view))
        if (depth < 3.0) return null
        val next = front.add(direction.scale(depth))
        return Vec3(next.x, settled.map { it.position.y }.sorted()[settled.size / 2], next.z)
    }
}
