package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Pure assignment and progress rules, independently testable without a running world. */
object TacticalManeuvers {
    fun assign(squadId: UUID, plan: TacticalPlan, view: TacticalSnapshot) {
        plan.tasks.clear()
        if (plan.pattern in setOf(TacticalPattern.FOLLOW_ORDER, TacticalPattern.EVADE)) return
        val focus = plan.focus ?: view.home ?: view.center
        val forward = focus.subtract(view.center).multiply(1.0, 0.0, 1.0).normalize()
            .let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }
        val side = Vec3(-forward.z, 0.0, forward.x)
        val fighters = view.members.filter { it.ready && it.role != NpcClass.MEDIC }
            .sortedWith(compareBy<TacticalMember> { if (it.role in SUPPORT_ROLES) 0 else 1 }.thenBy { it.id })
        val support = fighters.take(maxOf(1, fighters.size / 3)).map { it.id }.toSet()
        for ((index, member) in view.members.withIndex()) {
            if (view.order == SquadOrder.DEFEND && view.home != null &&
                member.position.distanceTo(view.home) > SquadFormation.perimeterRadius(view.members.size) + 10.0) continue
            val lateral = side.scale((index % 3 - 1) * 5.0)
            var job = TacticalJob.COVER
            var anchor = member.position
            when (plan.pattern) {
                TacticalPattern.FLANK, TacticalPattern.DISLODGE -> {
                    if (member.id !in support) {
                        job = if (plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.FLANK
                        val direction = if (squadId.leastSignificantBits and 1L == 0L) 1.0 else -1.0
                        anchor = view.center.add(forward.scale(10.0)).add(side.scale(direction * (16.0 + index % 2 * 5.0))).add(lateral)
                    }
                }
                TacticalPattern.BOUND -> {
                    val half = fighters.indexOf(member).coerceAtLeast(0) % 2
                    if (half == plan.movingHalf && focus.distanceTo(view.center) > 24.0) {
                        job = if (plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.ADVANCE
                        anchor = view.center.add(forward.scale(12.0)).add(lateral)
                    }
                }
                TacticalPattern.SEARCH -> {
                    job = if (index < 2) TacticalJob.SEARCH else TacticalJob.OBSERVE
                    anchor = if (index < 2) towards(member.position, focus, 12.0).add(lateral) else member.position
                }
                TacticalPattern.REORGANIZE, TacticalPattern.BREAK_CONTACT, TacticalPattern.AVOID_ARMOUR -> {
                    job = if (member.id in support) TacticalJob.COVER else TacticalJob.REGROUP
                    anchor = view.center.subtract(forward.scale(10.0)).add(lateral)
                }
                TacticalPattern.CONSOLIDATE -> {
                    job = TacticalJob.OBSERVE
                    val angle = index * Math.PI * 2.0 / view.members.size.coerceAtLeast(1)
                    anchor = (view.home ?: view.center).add(Vec3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)).scale(SquadFormation.perimeterRadius(view.members.size)))
                }
                TacticalPattern.RETURN_FIRE -> { job = TacticalJob.OBSERVE; anchor = view.center.subtract(forward.scale(5.0)).add(lateral) }
                else -> { anchor = view.center.add(lateral) }
            }
            if (member.role == NpcClass.MEDIC || !member.ready || member.health < 0.3) {
                job = TacticalJob.RESERVE
                anchor = view.center.subtract(forward.scale(8.0)).add(lateral)
            }
            plan.tasks[member.id] = TacticalTask(job, anchor, plan.focus, plan.id)
        }
    }

    fun advance(squadId: UUID, state: SquadTacticalState, plan: TacticalPlan, view: TacticalSnapshot) {
        plan.tasks.keys.retainAll(view.members.map { it.id }.toSet())
        val maneuver = plan.pattern in setOf(TacticalPattern.FLANK, TacticalPattern.DISLODGE, TacticalPattern.BOUND)
        if (!maneuver) { plan.status = TacticalStatus.EXECUTING; return }
        val covering = view.members.filter { plan.tasks[it.id]?.job == TacticalJob.COVER }
        if (plan.status == TacticalStatus.PREPARING) {
            val ready = covering.any { it.canFire && settled(it, plan.tasks[it.id]) }
            if (ready) {
                plan.status = TacticalStatus.EXECUTING
                plan.phaseSince = view.now
                val previous = HashMap(plan.tasks)
                assign(squadId, plan, view)
                // A covering position remains fixed while the other group starts its maneuver.
                for ((id, task) in previous) if (task.job == TacticalJob.COVER) plan.tasks[id] = task
            } else if (view.now - plan.phaseSince > 100) state.fail(view.now)
            return
        }
        val movers = view.members.filter { plan.tasks[it.id]?.job in setOf(TacticalJob.FLANK, TacticalJob.ADVANCE) }
        if (movers.isEmpty()) return
        if (covering.any { it.canFire }) plan.lastCover = view.now
        if (view.now - plan.lastCover > 40 && view.visible.isNotEmpty()) {
            for (member in movers) plan.tasks[member.id] = TacticalTask(TacticalJob.COVER, member.position, plan.focus, plan.id)
            plan.status = TacticalStatus.REGROUPING
            state.fail(view.now)
            return
        }
        if (movers.all { settled(it, plan.tasks[it.id]) }) {
            if (plan.pattern == TacticalPattern.BOUND && ++plan.bounds < 3 && (plan.focus?.distanceTo(view.center) ?: 0.0) > 24.0) {
                plan.movingHalf = 1 - plan.movingHalf
                plan.status = TacticalStatus.PREPARING
                plan.phaseSince = view.now
                assign(squadId, plan, view)
            } else for (member in movers) plan.tasks[member.id] = TacticalTask(TacticalJob.COVER, member.position, plan.focus, plan.id)
        } else if (view.now - plan.phaseSince > 160) state.fail(view.now)
    }

    private fun settled(member: TacticalMember, task: TacticalTask?): Boolean = task?.position?.let { member.position.distanceTo(it) <= 2.5 } == true
    private fun towards(from: Vec3, to: Vec3, distance: Double): Vec3 = from.add(to.subtract(from).normalize().scale(minOf(distance, from.distanceTo(to))))
    private val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
}
