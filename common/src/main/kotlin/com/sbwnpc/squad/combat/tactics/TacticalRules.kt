package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3

/** Order limits and squad-level observations shared by post search and snapshots. */
object TacticalRules {
    /** A defender's post stays within [radius] of the point it defends. */
    fun withinOrder(view: TacticalSnapshot, candidate: Vec3, radius: Double): Boolean = view.order != SquadOrder.DEFEND || view.home == null || candidate.distanceTo(view.home) <= radius

    /** Most of the engaged members are blocked: the squad is stalled. */
    fun stalled(blocked: Int, engaged: Int): Boolean = engaged >= 2 && blocked >= maxOf(2, (engaged + 1) / 2)
}
