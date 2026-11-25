package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * One squad's shared tactical observations: the contacts its members report and the latest
 * snapshot the HTN controller planned from. No entity or world references.
 */
class SquadTacticalState {
    val contacts = LinkedHashMap<UUID, TacticalContact>()
    var snapshot: TacticalSnapshot? = null
    var nextAssessment = Long.MIN_VALUE
    var nextTrace = Long.MIN_VALUE
    var peakStrength = 0

    /**
     * How far from home each defender stood when the contact found him, never more than where he
     * has stood since: the farthest a post of his may lie while the contact lasts, see
     * [TacticalRules.withinOrder]. Kept for one order and one home, and cleared when the contact ends.
     */
    val reach = HashMap<UUID, Double>()
    var reachStamp = -1
    var reachHome: Vec3? = null
}
