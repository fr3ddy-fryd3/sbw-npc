package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3

/** Protected support posts around a fixed defensive assignment, never around each new stop. */
object DefensiveOverwatch {
    const val RADIUS = 32.0
    val PATTERNS = setOf(TacticalPattern.CONSOLIDATE,TacticalPattern.REORIENT,TacticalPattern.HOLD_HEIGHT,TacticalPattern.RETURN_FIRE)

    fun enabled(view: TacticalSnapshot,member: TacticalMember,pattern: TacticalPattern): Boolean =
        view.order == SquadOrder.DEFEND && pattern in PATTERNS && member.ready && member.health >= 0.3 &&
            member.role in setOf(NpcClass.SNIPER,NpcClass.MACHINE_GUNNER)

    fun within(anchor: Vec3,point: Vec3): Boolean = point.subtract(anchor).horizontalDistance() <= RADIUS &&
        kotlin.math.abs(point.y-anchor.y) <= RADIUS

    fun probes(anchor: Vec3): List<Vec3> {
        val offsets=linkedSetOf(Vec3.ZERO)
        for (x in -32..32 step 4) for (z in -32..32 step 4)
            if (x*x+z*z <= RADIUS*RADIUS) offsets+=Vec3(x.toDouble(),0.0,z.toDouble())
        // Denser sampling next to the assigned post catches small ledges and parapets.
        for (x in -8..8 step 2) for (z in -8..8 step 2) offsets+=Vec3(x.toDouble(),0.0,z.toDouble())
        return offsets.flatMap { offset -> listOf(24.0,8.0,-8.0,-24.0).map { anchor.add(offset).add(0.0,it,0.0) } }
    }

    /** Body protected, eyes able to fire; a distant hill alone does not make an open post cover. */
    fun protected(point: Vec3,eyeHeight: Double,focus: Vec3?,blocked: (Vec3,Vec3)->Boolean): Boolean {
        val body=point.add(0.0,0.9,0.0)
        val eye=point.add(0.0,eyeHeight,0.0)
        if (focus != null) {
            val enemyEye=focus.add(0.0,1.5,0.0)
            if (blocked(eye,enemyEye)) return false
            val toward=enemyEye.subtract(body).normalize().scale(2.5)
            return blocked(body,body.add(toward))
        }
        val directions=(0..7).map { Vec3(kotlin.math.cos(it*Math.PI/4),0.0,kotlin.math.sin(it*Math.PI/4)).scale(2.5) }
        val sheltered=directions.filter { blocked(body,body.add(it)) }
        return sheltered.size >= 2 && sheltered.any { !blocked(eye,eye.add(it.scale(6.0))) }
    }

    fun score(anchor: Vec3,origin: Vec3,point: Vec3,exposure: Int): Double =
        -point.y*1000.0 + point.distanceTo(anchor) + point.distanceTo(origin)*0.2 + exposure*5.0

    fun preservePosts(plan: TacticalPlan,previous: Map<java.util.UUID,TacticalTask>,view: TacticalSnapshot) {
        if (view.order != SquadOrder.DEFEND || plan.pattern !in PATTERNS) return
        for ((id,next) in plan.tasks.toMap()) {
            val old=previous[id] ?: continue
            if (next.job != TacticalJob.OVERWATCH || old.job != TacticalJob.OVERWATCH) continue
            plan.tasks[id]=TacticalTask(next.job,old.anchor,next.focus,plan.id).also {
                it.position=old.position
                it.search=if (old.focus==next.focus) old.search else null
                it.nextSearch=if (it.search != null || it.position != null) old.nextSearch else view.now
                it.lastProgress=old.lastProgress
                it.closest=old.closest
                it.nextValidation=view.now
                it.failures=old.failures
            }
        }
    }
}
