package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/** A built route kept for the next ray turn; live clearance is reviewed by its caller. */
internal class TacticalRouteValidation(val path: Path, val builtAt: Long, val candidate: Vec3 = Vec3.atBottomCenterOf(path.target)) {
    fun fresh(now: Long): Boolean = now - builtAt <= 20
}
