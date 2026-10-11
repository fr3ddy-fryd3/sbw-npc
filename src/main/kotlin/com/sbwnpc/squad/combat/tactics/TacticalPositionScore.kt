package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.phys.Vec3

/** Firing posts prioritize shelter; covered transit uses neutral shelter ranks and goal distance. */
data class TacticalPositionScore(val open: Boolean, val exposure: Int, val cost: Double, val purpose: Purpose = Purpose.FIRING_POST) : Comparable<TacticalPositionScore> {
    enum class Purpose { FIRING_POST, DEPLOYMENT, COVERED_TRANSIT }
    override fun compareTo(other: TacticalPositionScore): Int = compareValuesBy(this, other, { it.open }, { it.exposure }, { it.cost })

    /** Native multi-target A* chooses a short route, so transit batches must preserve progress. */
    fun sameRouteTier(other: TacticalPositionScore): Boolean =
        open == other.open && exposure == other.exposure &&
            (purpose != Purpose.COVERED_TRANSIT || (other.purpose == purpose && other.cost <= cost + TRANSIT_COST_TOLERANCE))

    companion object {
        /** Under actual covering fire, transit should converge; shelter ranks the final firing post. */
        fun coveredTransitStep(origin: Vec3, goal: Vec3, point: Vec3) = TacticalPositionScore(false, 0, point.distanceTo(goal) + point.distanceTo(origin) * STEP_LENGTH_WEIGHT, Purpose.COVERED_TRANSIT)

        private const val STEP_LENGTH_WEIGHT = 0.1
        private const val TRANSIT_COST_TOLERANCE = 2.0
    }
}
