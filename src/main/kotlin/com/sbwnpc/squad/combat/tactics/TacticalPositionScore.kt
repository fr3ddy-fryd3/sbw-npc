package com.sbwnpc.squad.combat.tactics

/** Reachable shelter wins over a shorter exposed move; distance only breaks quality ties. */
data class TacticalPositionScore(val open: Boolean, val exposure: Int, val cost: Double) : Comparable<TacticalPositionScore> {
    override fun compareTo(other: TacticalPositionScore): Int = compareValuesBy(this, other, { it.open }, { it.exposure }, { it.cost })
}
