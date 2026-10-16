package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3

/** Maneuver constraints supplement the existing collision, sightline and navigation checks. */
interface TacticalPositionPolicy {
    fun additionalProbes(view: TacticalSnapshot, task: TacticalTask, origin: Vec3, role: NpcClass): List<Vec3> = emptyList()
    fun readyBatch(task: TacticalTask, anchor: Vec3, scored: Map<Vec3, TacticalPositionScore>): Boolean = false
    fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3): Boolean = true
    fun route(view: TacticalSnapshot, point: Vec3): Boolean = true
    fun route(view: TacticalSnapshot, task: TacticalTask, origin: Vec3, point: Vec3): Boolean = route(view, point)
    fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int): Double = 0.0
    val permitsExposedFlankAdvance: Boolean get() = false
    fun requiresShelter(view: TacticalSnapshot, task: TacticalTask): Boolean = false

    /** An assault closes on the enemy it was ordered to take: no role clearance from contacts. */
    val closesWithEnemy: Boolean get() = false
}

object DefaultTacticalPositions : TacticalPositionPolicy
object BoundingPositions : TacticalPositionPolicy {
    override fun requiresShelter(view: TacticalSnapshot, task: TacticalTask): Boolean = task.job == TacticalJob.ADVANCE && (view.incoming.isNotEmpty() || view.members.any { it.suppressed })
}
object EncirclePositions : TacticalPositionPolicy {
    override fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3): Boolean = task.job != TacticalJob.FLANK || task.focus?.let { point.subtract(it).horizontalDistance() >= 40.0 } != false
}
object WithdrawalPositions : TacticalPositionPolicy {
    override fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int) = exposure * 10.0
}
object HeightAttackPositions : TacticalPositionPolicy {
    override fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int): Double {
        if (task.job !in TacticalJob.RUNNING) return 0.0
        val focus = task.focus ?: return 0.0
        val remainingHeight = maxOf(0.0, focus.y - point.y)
        return remainingHeight * 1.5
    }
}
object HoldHeightPositions : TacticalPositionPolicy {
    override fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3) = route(view, point)
    override fun route(view: TacticalSnapshot, point: Vec3) = point.y >= view.center.y - 2.0
    override fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int) = maxOf(0.0, view.center.y - point.y) * 8.0
}
object FilePositions : TacticalPositionPolicy {
    override fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3) = task.job != TacticalJob.ADVANCE || point.distanceTo(localAnchor) <= 1.0
}
