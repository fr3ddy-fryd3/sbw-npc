package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3

/** Maneuver constraints supplement the existing collision, sightline and navigation checks. */
interface TacticalPositionPolicy {
    fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3): Boolean = true
    fun route(view: TacticalSnapshot, point: Vec3): Boolean = true
    fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int): Double = 0.0
    val permitsExposedFlankAdvance: Boolean get() = false
}

object DefaultTacticalPositions : TacticalPositionPolicy
object EncirclePositions : TacticalPositionPolicy { override val permitsExposedFlankAdvance = true }
object WithdrawalPositions : TacticalPositionPolicy {
    override fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int) = exposure * 10.0
}
object HeightAttackPositions : TacticalPositionPolicy {
    override fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int): Double =
        if (task.job in TacticalJob.RUNNING) task.focus?.let { maxOf(0.0, it.y - point.y) * 1.5 } ?: 0.0 else 0.0
}
object HoldHeightPositions : TacticalPositionPolicy {
    override fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3) = route(view, point)
    override fun route(view: TacticalSnapshot, point: Vec3) = point.y >= view.center.y - 2.0
    override fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int) = maxOf(0.0, view.center.y - point.y) * 8.0
}
object FilePositions : TacticalPositionPolicy {
    override fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3) =
        task.job != TacticalJob.ADVANCE || point.distanceTo(localAnchor) <= 1.0
}
