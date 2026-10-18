package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
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

    /** How near a known enemy a post or route may come; an assault cuts it, never drops it (#88). */
    fun clearance(role: NpcClass): Double = CombatPositionSafety.clearance(role)
}

object DefaultTacticalPositions : TacticalPositionPolicy
