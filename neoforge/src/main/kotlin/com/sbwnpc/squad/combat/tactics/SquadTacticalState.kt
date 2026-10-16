package com.sbwnpc.squad.combat.tactics

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
}
