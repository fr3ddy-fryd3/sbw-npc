package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3

/** Order limits and squad-level observations shared by post search and snapshots. */
object TacticalRules {
    /**
     * A defender's post stays within [radius] of the point it defends, or, searched round a point
     * [from] still outside it, no farther out than that point. Met by the enemy on his way in, a
     * defender fights where the contact found him, and every post round him lay outside the area:
     * all were refused and the squad stood bunched in the open short of it (#266). Measured from
     * the search's centre, not from him: his posts take him a little nearer the area each time,
     * and from where he stands the next search round the same centre would have none left. A man
     * pushed out of the area keeps posts no farther out than where it put him; he is not walked
     * back mid-fight, as one met short of it is not marched in.
     */
    fun withinOrder(view: TacticalSnapshot, candidate: Vec3, radius: Double, from: Vec3): Boolean {
        val home = view.home
        if (view.order != SquadOrder.DEFEND || home == null) return true
        val distance = candidate.distanceTo(home)
        return distance <= radius || distance <= from.distanceTo(home)
    }

    /** Most of the engaged members are blocked: the squad is stalled. */
    fun stalled(blocked: Int, engaged: Int): Boolean = engaged >= 2 && blocked >= maxOf(2, (engaged + 1) / 2)
}
