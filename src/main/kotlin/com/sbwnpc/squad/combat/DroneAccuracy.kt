package com.sbwnpc.squad.combat

/** Shared tuning for infantry and AI-operated vehicle weapons, in SBW spread units. */
object DroneAccuracy {
    const val SPREAD_MULTIPLIER = 2.5
    const val MIN_SPREAD = 3.0

    fun adjustSpread(baseSpread: Double, droneTarget: Boolean): Double =
        if (droneTarget) maxOf(baseSpread * SPREAD_MULTIPLIER, MIN_SPREAD) else baseSpread
}
