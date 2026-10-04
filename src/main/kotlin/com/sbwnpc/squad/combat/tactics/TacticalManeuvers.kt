package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Pure assignment and progress rules, independently testable without a running world. */
object TacticalManeuvers {
    /** Turning to a new sector does not invalidate an already chosen walking destination. */
    fun refreshSectors(plan: TacticalPlan,view: TacticalSnapshot) {
        if (plan.pattern!=TacticalPattern.REORIENT) return
        for (member in view.members) {
            val task=plan.tasks[member.id] ?: continue
            val focus=view.visible.minByOrNull { it.position.distanceToSqr(member.position) }?.position ?: continue
            if (task.position==null && task.focus?.distanceTo(focus)?.let { it>8.0 }==true) task.search=null
            task.focus=focus
        }
    }

    fun assign(squadId: UUID, plan: TacticalPlan, view: TacticalSnapshot) {
        plan.tasks.clear()
        if (plan.pattern in setOf(TacticalPattern.FOLLOW_ORDER)) return
        if (plan.origin == null) plan.origin = view.center
        val focus = plan.focus ?: view.home ?: view.center
        val forward = focus.subtract(view.center).multiply(1.0, 0.0, 1.0).normalize()
            .let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }
        val side = Vec3(-forward.z, 0.0, forward.x)
        val fighters = view.members.filter { it.ready && it.health >= 0.3 && it.role != NpcClass.MEDIC }
            .filter { it.id !in plan.failedMembers }
            .sortedWith(compareBy<TacticalMember> { if (it.canFire) 0 else 1 }
                .thenBy { if (it.role in SUPPORT_ROLES) 0 else 1 }.thenBy { it.id })
        val fileOrder = fighters.sortedBy { it.position.distanceToSqr(focus) }.filter { it.id !in plan.passed }
        val fileMovers = fileOrder.take(2).map { it.id }.toSet()
        val support = fighters.take(maxOf(1, fighters.size / 3)).map { it.id }.toSet()
        if (plan.pattern in FLANK_PATTERNS) {
            plan.flankSupport.retainAll(fighters.map { it.id }.toSet())
            if (plan.flankSupport.isEmpty()) plan.flankSupport.addAll(support)
        }
        if (plan.pattern == TacticalPattern.ATTACK_HEIGHT) {
            plan.heightSupport.retainAll(fighters.map { it.id }.toSet())
            if (plan.heightSupport.isEmpty()) plan.heightSupport.addAll(support)
        }
        val patient = view.members.filter { it.health < 0.6 || it.role == NpcClass.MEDIC }.minByOrNull { it.health }
        val guard = if (fighters.size >= 4 && patient != null) fighters.filter { it.id != patient.id && it.id !in support }
            .minByOrNull { it.position.distanceToSqr(patient.position) }?.id else null
        val escape = escapeDirection(view,forward)
        for ((index, member) in view.members.withIndex()) {
            if (member.id in plan.failedMembers) continue
            val overwatch=DefensiveOverwatch.enabled(view,member,plan.pattern)
            val homeRadius=SquadFormation.perimeterRadius(view.members.size)+10.0+if (overwatch) DefensiveOverwatch.RADIUS else 0.0
            if (member.position.distanceTo(view.center) > if (overwatch) 80.0 else 48.0) continue
            if (view.order == SquadOrder.DEFEND && view.home != null &&
                member.position.distanceTo(view.home) > homeRadius) continue
            val lateral = side.scale((index % 3 - 1) * 5.0)
            var job = TacticalJob.COVER
            var anchor = member.position
            var assignedFocus = plan.focus
            when (plan.pattern) {
                TacticalPattern.FLANK, TacticalPattern.ENCIRCLE, TacticalPattern.DISLODGE -> {
                    if (member.id !in plan.flankSupport) {
                        job = if (plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.FLANK
                        val direction = if (plan.flankSide != 0.0) plan.flankSide else if (squadId.leastSignificantBits and 1L == 0L) 1.0 else -1.0
                        anchor = view.center.add(forward.scale(10.0)).add(side.scale(direction * (16.0 + index % 2 * 5.0))).add(lateral)
                        if (plan.pattern == TacticalPattern.ENCIRCLE) {
                            val attackers = fighters.filter { it.id !in support }
                            val slot = attackers.indexOf(member).coerceAtLeast(0)
                            val angle = slot * Math.PI * 2.0 / attackers.size.coerceAtLeast(1)
                            anchor = focus.add(forward.scale(kotlin.math.cos(angle)*18.0)).add(side.scale(kotlin.math.sin(angle)*18.0))
                        }
                    }
                }
                TacticalPattern.ATTACK_HEIGHT -> {
                    val catchingUp = plan.bounds % 2 == 1
                    val moves = (member.id in plan.heightSupport) == catchingUp
                    if (moves) {
                        job = if (plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.ADVANCE
                        val front = plan.heightFront ?: view.center
                        if (catchingUp) anchor = front.add(lateral)
                        else {
                            val uphill = focus.subtract(front).multiply(1.0,0.0,1.0).normalize()
                                .let { if (it.lengthSqr() < 0.01) forward else it }
                            val direction = if (plan.flankSide != 0.0) plan.flankSide else if (squadId.leastSignificantBits and 1L == 0L) 1.0 else -1.0
                            val approach = if (plan.bounds == 0) side.scale(direction * (10.0 + index % 2 * 4.0)).add(lateral) else lateral
                            anchor = front.add(uphill.scale(minOf(12.0,focus.subtract(front).horizontalDistance())))
                                .add(approach).add(0.0,minOf(8.0,maxOf(0.0,focus.y-front.y)),0.0)
                        }
                    }
                }
                TacticalPattern.BOUND, TacticalPattern.PURSUE, TacticalPattern.FOCUS_SECTOR -> {
                    val half = if (plan.bounds == 0 && member.role in SUPPORT_ROLES) 1 else fighters.indexOf(member).coerceAtLeast(0) % 2
                    val allowed = plan.pattern != TacticalPattern.PURSUE || member.position.distanceTo(plan.origin!!) < 32.0
                    if (half == plan.movingHalf && allowed && focus.distanceTo(view.center) > 24.0) {
                        job = if (plan.status == TacticalStatus.PREPARING) TacticalJob.WAIT else TacticalJob.ADVANCE
                        anchor = view.center.add(forward.scale(12.0)).add(lateral)
                        if (plan.pattern == TacticalPattern.PURSUE) anchor = towards(plan.origin!!,anchor,32.0)
                    } else if (plan.pattern == TacticalPattern.FOCUS_SECTOR) {
                        assignedFocus = view.visible.sortedByDescending { it.priority }.getOrNull(index % view.visible.size.coerceAtLeast(1))?.position ?: focus
                    }
                }
                TacticalPattern.HOLD_HEIGHT -> {
                    anchor = member.position
                    job = TacticalJob.COVER
                }
                TacticalPattern.FILE -> {
                    val coverId = if (view.visible.isNotEmpty()) fileOrder.lastOrNull()?.id else null
                    job = when {
                        member.id == coverId -> TacticalJob.COVER
                        member.id in plan.passed -> TacticalJob.OBSERVE
                        member.id in fileMovers && (view.visible.isEmpty() || plan.status == TacticalStatus.EXECUTING) -> TacticalJob.ADVANCE
                        else -> TacticalJob.WAIT
                    }
                    anchor = when {
                        member.id in plan.passed -> towards(member.position,focus,8.0).add(side.scale(if (index % 2 == 0) 4.0 else -4.0))
                        job == TacticalJob.ADVANCE -> towards(member.position,focus,10.0)
                        else -> member.position
                    }
                }
                TacticalPattern.SEARCH -> {
                    job = if (index < 2) TacticalJob.SEARCH else TacticalJob.OBSERVE
                    anchor = if (index < 2) focus.add(side.scale(if (index % 2 == 0) 5.0 else -5.0)) else member.position
                }
                TacticalPattern.REORGANIZE, TacticalPattern.BREAK_CONTACT, TacticalPattern.AVOID_ARMOUR -> {
                    val half = fighters.indexOf(member).coerceAtLeast(0) % 2
                    job = if (half != plan.movingHalf && member.ready && plan.pattern != TacticalPattern.AVOID_ARMOUR)
                        TacticalJob.COVER else if (plan.status == TacticalStatus.PREPARING && plan.pattern != TacticalPattern.AVOID_ARMOUR)
                        TacticalJob.WAIT else TacticalJob.RETREAT
                    anchor = if (job == TacticalJob.COVER) member.position else view.center.add(escape.scale(12.0)).add(lateral)
                    if (plan.pattern == TacticalPattern.REORGANIZE) {
                        anchor = towards(plan.origin!!,anchor,32.0)
                        if (member.position.distanceTo(plan.origin!!) >= 30.0) { job=TacticalJob.COVER; anchor=member.position }
                    }
                }
                TacticalPattern.REORIENT -> {
                    assignedFocus = view.visible.minByOrNull { it.position.distanceToSqr(member.position) }?.position ?: focus
                    anchor = member.position
                }
                TacticalPattern.REPEL -> {
                    anchor = if (member.role in SUPPORT_ROLES) member.position.subtract(forward.scale(5.0)) else member.position.add(lateral.scale(0.5))
                    job = TacticalJob.COVER
                }
                TacticalPattern.ANTI_ARMOUR -> {
                    if (member.rockets) {
                        job = TacticalJob.ANTI_ARMOUR
                        anchor = member.position.add(side.scale(if (index % 2 == 0) 5.0 else -5.0))
                    } else {
                        assignedFocus = view.visible.filter { !it.armoured }.minByOrNull { it.position.distanceToSqr(member.position) }?.position
                        job = if (assignedFocus != null) TacticalJob.COVER else TacticalJob.OBSERVE
                        anchor = member.position.subtract(forward.scale(4.0)).add(lateral)
                    }
                }
                TacticalPattern.EVADE -> {
                    job = TacticalJob.REGROUP
                    val angle = index * Math.PI * 2.0 / view.members.size.coerceAtLeast(1)
                    anchor = member.position.add(Vec3(kotlin.math.cos(angle),0.0,kotlin.math.sin(angle)).scale(8.0))
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
            if (member.id == guard && patient != null && plan.pattern !in setOf(TacticalPattern.EVADE,TacticalPattern.FILE) &&
                !(plan.pattern == TacticalPattern.ATTACK_HEIGHT && plan.bounds % 2 == 1)) {
                job = TacticalJob.COVER
                anchor = patient.position.subtract(forward.scale(4.0)).add(side.scale(4.0))
            }
            if (overwatch && member.id != guard) {
                job=TacticalJob.OVERWATCH
                val angle=index*Math.PI*2.0/view.members.size.coerceAtLeast(1)
                anchor=(view.home ?: view.center).add(Vec3(kotlin.math.cos(angle),0.0,kotlin.math.sin(angle))
                    .scale(SquadFormation.perimeterRadius(view.members.size)))
            }
            plan.tasks[member.id] = TacticalTask(job, anchor, assignedFocus, plan.id)
        }
    }

    fun advance(squadId: UUID, state: SquadTacticalState, plan: TacticalPlan, view: TacticalSnapshot) {
        plan.tasks.keys.retainAll(view.members.map { it.id }.toSet())
        if (plan.pattern == TacticalPattern.FILE && view.visible.isEmpty()) plan.status = TacticalStatus.EXECUTING
        val retreating = plan.pattern in setOf(TacticalPattern.REORGANIZE,TacticalPattern.BREAK_CONTACT,TacticalPattern.AVOID_ARMOUR)
        val maneuver = retreating || plan.pattern in setOf(TacticalPattern.FLANK,TacticalPattern.ENCIRCLE,TacticalPattern.DISLODGE,
            TacticalPattern.ATTACK_HEIGHT,TacticalPattern.BOUND,TacticalPattern.PURSUE,TacticalPattern.FOCUS_SECTOR,TacticalPattern.FILE)
        if (!maneuver) { plan.status = TacticalStatus.EXECUTING; return }
        if (plan.pattern in FLANK_PATTERNS + TacticalPattern.ATTACK_HEIGHT && plan.bounds == 0 && plan.status == TacticalStatus.PREPARING &&
            view.members.none { plan.tasks[it.id]?.job == TacticalJob.COVER && covers(it,plan.tasks[it.id]) } &&
            view.members.any { plan.tasks[it.id]?.job == TacticalJob.WAIT && covers(it,plan.tasks[it.id]) }) {
            // Contact was acquired before anyone finished aiming. Use the firing lane which
            // actually became ready, even if its shooter originally belonged to the advance.
            plan.heightSupport.clear()
            plan.flankSupport.clear()
            assign(squadId,plan,view)
            for (member in view.members) {
                val task = plan.tasks[member.id] ?: continue
                if (task.job == TacticalJob.COVER && covers(member,task)) task.position=member.position
            }
        }
        val covering = view.members.filter { plan.tasks[it.id]?.job == TacticalJob.COVER }
        if (plan.status == TacticalStatus.PREPARING) {
            val ready = covering.any { covers(it,plan.tasks[it.id]) && settled(it,plan.tasks[it.id]) } ||
                (plan.pattern == TacticalPattern.ATTACK_HEIGHT && view.visible.isEmpty()) ||
                (retreating && (plan.pattern == TacticalPattern.AVOID_ARMOUR || view.now-plan.phaseSince >= 40))
            if (ready) {
                plan.status = if (retreating) TacticalStatus.REGROUPING else TacticalStatus.EXECUTING
                plan.phaseSince = view.now
                plan.lastCover = view.now
                val previous = HashMap(plan.tasks)
                assign(squadId, plan, view)
                // A covering position remains fixed while the other group starts its maneuver.
                for ((id, task) in previous) if (task.job == TacticalJob.COVER) plan.tasks[id] = task
            } else if (view.now - plan.phaseSince > 100) state.fail(view.now)
            return
        }
        val movers = view.members.filter { plan.tasks[it.id]?.job in setOf(TacticalJob.FLANK, TacticalJob.ADVANCE, TacticalJob.RETREAT) }
        if (movers.isEmpty()) return
        if (covering.any { covers(it,plan.tasks[it.id]) }) plan.lastCover = view.now
        if (!retreating && view.now - plan.lastCover > 80 && view.visible.isNotEmpty()) {
            for (member in movers) plan.tasks[member.id] = TacticalTask(TacticalJob.COVER, member.position, plan.focus, plan.id)
            plan.status = TacticalStatus.REGROUPING
            state.fail(view.now)
            return
        }
        if (movers.all { settled(it, plan.tasks[it.id]) }) {
            if (plan.pattern == TacticalPattern.FILE) {
                plan.passed.addAll(movers.map { it.id })
                plan.phaseSince = view.now
                assign(squadId,plan,view)
                if (plan.passed.size >= view.fighting.count { it.role != NpcClass.MEDIC }) plan.status = TacticalStatus.COMPLETED
            } else if (plan.pattern == TacticalPattern.ATTACK_HEIGHT) {
                if (plan.bounds % 2 == 0) plan.heightFront = Vec3(
                    movers.map { it.position.x }.sorted()[movers.size/2],
                    movers.map { it.position.y }.sorted()[movers.size/2],
                    movers.map { it.position.z }.sorted()[movers.size/2])
                plan.bounds++
                val front = plan.heightFront!!
                if (plan.bounds % 2 == 0 && plan.focus?.let {
                    front.y >= it.y-2.0 && it.subtract(front).horizontalDistance() <= 24.0
                } == true) plan.status = TacticalStatus.COMPLETED
                else {
                    plan.status = TacticalStatus.PREPARING
                    plan.phaseSince = view.now
                    assign(squadId,plan,view)
                }
            } else if (plan.pattern in setOf(TacticalPattern.BOUND,TacticalPattern.PURSUE,TacticalPattern.FOCUS_SECTOR,
                TacticalPattern.REORGANIZE,TacticalPattern.BREAK_CONTACT) && ++plan.bounds < 3 && (plan.focus?.distanceTo(view.center) ?: 0.0) > 24.0) {
                plan.movingHalf = 1 - plan.movingHalf
                plan.status = TacticalStatus.PREPARING
                plan.phaseSince = view.now
                assign(squadId, plan, view)
            } else for (member in movers) plan.tasks[member.id] = TacticalTask(TacticalJob.COVER, member.position, plan.focus, plan.id)
        } else if (view.now - plan.phaseSince > (if (plan.pattern in setOf(TacticalPattern.ENCIRCLE,TacticalPattern.ATTACK_HEIGHT)) 400 else 160)) state.fail(view.now)
    }

    /** A local routing failure gives only this member back to the existing individual AI. */
    fun abandon(plan: TacticalPlan, member: UUID) {
        plan.tasks.remove(member)
        plan.failedMembers.add(member)
    }

    internal fun covers(member: TacticalMember,task: TacticalTask?): Boolean {
        val focus=task?.focus
        return member.canFire && (member.firingAt==null || focus==null || member.firingAt.distanceTo(focus)<=18.0)
    }
    private fun settled(member: TacticalMember, task: TacticalTask?): Boolean = task?.position?.let {
        member.position.distanceTo(it) <= 2.5 && member.position.distanceTo(task.anchor) <= 10.0
    } == true
    private fun escapeDirection(view: TacticalSnapshot,forward: Vec3): Vec3 {
        val threats = view.visible.map { it.position } + view.incoming
        if (threats.isEmpty()) return forward.scale(-1.0)
        return (0..7).map { angle -> Vec3(kotlin.math.cos(angle*Math.PI/4.0),0.0,kotlin.math.sin(angle*Math.PI/4.0)) }
            .maxBy { direction ->
                val next = view.center.add(direction.scale(12.0))
                threats.minOf { it.distanceTo(next) } - (view.home?.let { next.distanceTo(it)*0.1 } ?: 0.0)
            }
    }
    private fun towards(from: Vec3, to: Vec3, distance: Double): Vec3 = from.add(to.subtract(from).normalize().scale(minOf(distance, from.distanceTo(to))))
    private val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
    private val FLANK_PATTERNS = setOf(TacticalPattern.FLANK,TacticalPattern.ENCIRCLE,TacticalPattern.DISLODGE)
}
