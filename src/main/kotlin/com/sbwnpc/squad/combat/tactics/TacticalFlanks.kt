package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Stable fire teams share the existing tactical position search and navigator. */
object TacticalFlanks {
    val PATTERNS=setOf(TacticalPattern.FLANK,TacticalPattern.ENCIRCLE,TacticalPattern.DISLODGE,TacticalPattern.FOCUS_SECTOR)
    const val TEAM_SIZE=6

    fun pending(plan: TacticalPlan,view: TacticalSnapshot): List<UUID> {
        val alive=view.fighting.map { it.id }.toSet()
        return plan.flankOrder.filter { it in alive && it !in plan.flankArrived && it !in plan.failedMembers }
    }

    fun anchor(plan: TacticalPlan,view: TacticalSnapshot,slot: Int): Vec3 {
        val origin=plan.origin ?: view.center
        val focus=plan.focus ?: view.home ?: origin
        val forward=focus.subtract(origin).multiply(1.0,0.0,1.0).normalize().let {
            if (it.lengthSqr()<0.01) Vec3(0.0,0.0,1.0) else it
        }
        val side=Vec3(-forward.z,0.0,forward.x)
        if (plan.pattern==TacticalPattern.ENCIRCLE) {
            val angle=slot*Math.PI*2/plan.flankOrder.size.coerceAtLeast(1)
            return focus.add(forward.scale(kotlin.math.cos(angle)*24.0)).add(side.scale(kotlin.math.sin(angle)*24.0))
        }
        val projections=view.contacts.map { it.position.subtract(focus).dot(side) }
        val left=minOf(-20.0,(projections.minOrNull() ?: 0.0)-12.0)
        val right=maxOf(20.0,(projections.maxOrNull() ?: 0.0)+12.0)
        val both=plan.flankOrder.size>=8 && plan.pattern!=TacticalPattern.FOCUS_SECTOR
        val weak=if (kotlin.math.abs(left)<=right) -1.0 else 1.0
        val direction=if (both) (if (slot%2==0) -1.0 else 1.0) else if (plan.pattern==TacticalPattern.FOCUS_SECTOR) weak else plan.flankSide
        val lane=slot/(if (both) 2 else 1)
        val sideways=(if (direction<0) left else right)+direction*(lane%3)*4.0
        val distance=focus.subtract(origin).horizontalDistance()
        val depth=maxOf(10.0,distance-20.0-(lane/3)*4.0)
        return origin.add(forward.scale(depth)).add(side.scale(sideways))
    }

    fun staging(plan: TacticalPlan,slot: Int): Vec3 {
        val origin=plan.origin!!
        val forward=(plan.focus ?: origin.add(0.0,0.0,1.0)).subtract(origin).multiply(1.0,0.0,1.0).normalize()
        val side=Vec3(-forward.z,0.0,forward.x)
        return origin.subtract(forward.scale(6.0+(slot/8)*4.0)).add(side.scale((slot%8-3.5)*4.0))
    }

    fun requiredCover(view: TacticalSnapshot): Int = if (view.fighting.size>=12) 2 else 1
}
