package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID
import kotlin.math.hypot

/** An empty armed vehicle at [at] whose gun bears on the enemy a team faces. */
data class WeaponStation(val vehicle: UUID, val at: Point3)

/** A man of the team free to man a [WeaponStation], standing at [at]. */
data class StationHand(val id: UUID, val at: Point3)

/** Which of a team's men mans which vehicle's gun ([men]: vehicle to man), as its plan decided. */
data class StationPlan(val men: Map<UUID, UUID>)

/**
 * Who of a team in a fight mans the guns of the empty armed vehicles round it (#255): the team's
 * decision, where each man used to take the first free gun he found himself. One man per vehicle,
 * as many vehicles as there are; a man keeps the gun he was given while both are still there.
 */
object WeaponStations {
    /** How far a man walks to a vehicle's gun. */
    const val REACH = 20.0

    /** Each of [stations] given the nearest of [hands] within [REACH] not given another, keeping [previous]. */
    fun assign(previous: Map<UUID, UUID>, stations: List<WeaponStation>, hands: List<StationHand>): Map<UUID, UUID> {
        val present = hands.mapTo(HashSet()) { it.id }
        val men = previous.filterTo(LinkedHashMap()) { (vehicle, man) -> man in present && stations.any { it.vehicle == vehicle } }
        for (station in stations.filter { it.vehicle !in men }) {
            hands.filter { it.id !in men.values && flat(it.at, station.at) <= REACH }
                .minByOrNull { flat(it.at, station.at) }
                ?.let { men[station.vehicle] = it.id }
        }
        return men
    }

    private fun flat(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)
}
