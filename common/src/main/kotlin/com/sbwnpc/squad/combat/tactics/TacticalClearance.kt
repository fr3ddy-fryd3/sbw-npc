package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/** All remembered squad contacts constrain movement, including enemies outside the focus sector. */
object TacticalClearance {
    private const val CONTACT_MEMORY_TICKS = 200

    fun threats(view: TacticalSnapshot): List<Vec3> = view.contacts.filter { view.now - it.seenAt <= CONTACT_MEMORY_TICKS }.map { it.position }

    fun permitsPosition(role: NpcClass, origin: Vec3, point: Vec3, view: TacticalSnapshot): Boolean = permitsPosition(CombatPositionSafety.clearance(role), origin, point, view)

    fun permitsPosition(clearance: Double, origin: Vec3, point: Vec3, view: TacticalSnapshot): Boolean = threats(view).all { CombatPositionSafety.permits(origin, point, it, clearance) }

    fun permitsPath(role: NpcClass, origin: Vec3, path: Path, view: TacticalSnapshot): Boolean = permitsPath(CombatPositionSafety.clearance(role), origin, path, view)

    fun permitsPath(clearance: Double, origin: Vec3, path: Path, view: TacticalSnapshot): Boolean = CombatPositionSafety.permitsPath(origin, path, threats(view), clearance)
}
