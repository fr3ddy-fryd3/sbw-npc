package com.sbwnpc.squad.combat.tactics

/** Posts rank by shelter first, then by how many threats see them, then by cost. */
data class TacticalPositionScore(val open: Boolean, val exposure: Int, val cost: Double) : Comparable<TacticalPositionScore> {
    override fun compareTo(other: TacticalPositionScore): Int = compareValuesBy(this, other, { it.open }, { it.exposure }, { it.cost })

    /** Native multi-target A* picks the nearest of a batch, so a batch holds one shelter tier. */
    fun sameRouteTier(other: TacticalPositionScore): Boolean = open == other.open && exposure == other.exposure
}
