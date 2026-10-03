package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import kotlin.math.*

/** Short route legs prevent a rear flank destination from routing straight through the enemy. */
object TacticalRoutes {
    fun leg(from: Vec3, task: TacticalTask): Vec3 {
        val focus = task.focus
        if (task.job == TacticalJob.FLANK && focus != null) {
            val a = from.subtract(focus).multiply(1.0,0.0,1.0)
            val b = task.anchor.subtract(focus).multiply(1.0,0.0,1.0)
            if (a.lengthSqr() > 64.0 && b.lengthSqr() > 64.0) {
                val angle = acos(a.normalize().dot(b.normalize()).coerceIn(-1.0,1.0))
                if (angle > Math.PI/3.0) {
                    val turn = if (a.x*b.z-a.z*b.x >= 0.0) Math.PI/4.0 else -Math.PI/4.0
                    val unit = a.normalize()
                    val rotated = Vec3(unit.x*cos(turn)-unit.z*sin(turn),0.0,unit.x*sin(turn)+unit.z*cos(turn))
                    return Vec3(focus.x,from.y,focus.z).add(rotated.scale(a.length().coerceIn(18.0,24.0)))
                }
            }
        }
        val delta = task.anchor.subtract(from)
        return if (delta.length() > 24.0) from.add(delta.normalize().scale(24.0)) else task.anchor
    }
}
