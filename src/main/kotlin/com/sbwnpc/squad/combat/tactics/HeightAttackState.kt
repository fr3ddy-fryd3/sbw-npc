package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.UUID

class HeightAttackState : CoveredManeuverState() {
    override val positions = HeightAttackPositions
    override val stability = TacticalStability(committed = true, phaseLimit = 480)
    override val movementTimeout = 400L
    override val holdsFailedMembers = true
    override val alternatesSideAfterFailure = true
    private val covering = HashSet<UUID>()
    val support: Set<UUID> = Collections.unmodifiableSet(covering)
    var front: Vec3? = null
        private set

    override fun guardsPatient(): Boolean = bounds % 2 == 0
    override fun prepareAssignments(layout: TacticalLayout) {
        covering.retainAll(layout.fighters.map { it.id }.toSet())
        if (covering.isEmpty()) covering.addAll(layout.support)
    }

    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val catchingUp = bounds % 2 == 1
        if ((member.id in covering) != catchingUp) return layout.cover(member)
        val job = if (layout.plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.ADVANCE
        val point = front ?: layout.view.center
        val anchor = if (catchingUp) point.add(layout.lateral(index)) else {
            val uphill = layout.focus.subtract(point).multiply(1.0, 0.0, 1.0).normalize()
                .let { if (it.lengthSqr() < 0.01) layout.forward else it }
            val direction = if (layout.plan.flankSide != 0.0) layout.plan.flankSide
                else if (layout.context.squad.leastSignificantBits and 1L == 0L) 1.0 else -1.0
            val approach = if (bounds == 0) layout.side.scale(direction * (10.0 + index % 2 * 4.0)).add(layout.lateral(index))
                else layout.lateral(index)
            point.add(uphill.scale(minOf(12.0, layout.focus.subtract(point).horizontalDistance())))
                .add(approach).add(0.0, minOf(8.0, maxOf(0.0, layout.focus.y - point.y)), 0.0)
        }
        return layout.intent(job, anchor)
    }

    override fun promoteLateSupport(context: TacticalContext) {
        if (bounds != 0 || context.view.members.any {
            context.plan.tasks[it.id]?.job == TacticalJob.COVER && TacticalEvidence.covers(it, context.plan.tasks[it.id])
        } || context.view.members.none {
            context.plan.tasks[it.id]?.job == TacticalJob.WAIT && TacticalEvidence.covers(it, context.plan.tasks[it.id])
        }) return
        covering.clear()
        assign(context, trigger = "late_height_cover")
        for (member in context.view.members) {
            val task = context.plan.tasks[member.id] ?: continue
            if (task.job == TacticalJob.COVER && TacticalEvidence.covers(member, task)) task.position = member.position
        }
    }

    override fun readyWithoutCover(context: TacticalContext): Boolean = context.view.visible.isEmpty()
    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        if (bounds % 2 == 0) front = Vec3(movers.map { it.position.x }.sorted()[movers.size / 2],
            movers.map { it.position.y }.sorted()[movers.size / 2], movers.map { it.position.z }.sorted()[movers.size / 2])
        bounds++
        val point = front!!
        val done = bounds % 2 == 0 && context.plan.focus?.let {
            point.y >= it.y - 2.0 && it.subtract(point).horizontalDistance() <= 24.0
        } == true
        phases.transition(if (done) CompletedPhase() else PreparingPhase(), context, if (done) "height_reached" else "height_group_arrived")
    }
}
