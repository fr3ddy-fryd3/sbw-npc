package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID
import kotlin.math.hypot

/** The seat a travelling squad gave one of its men: in [vehicle], at the wheel if [driver]. */
data class RideSeat(val vehicle: UUID, val driver: Boolean)

/** A man of a travelling squad on foot, who may be given a seat, standing at [at]. */
data class SeatRider(val id: UUID, val at: Point3)

/**
 * A ground vehicle the squad may ride in: [free] seats its men may take (what others hold or sit in
 * left out), and whether someone already sits at its wheel ([driven]). [ours] when a man of the
 * squad is already aboard it.
 */
data class SeatVehicle(val id: UUID, val at: Point3, val free: Int, val driven: Boolean, val ours: Boolean = false)

/** Who of a travelling squad rides where ([seats]), and who walks for this order ([walks]). */
data class SeatPlan(val seats: Map<UUID, RideSeat> = emptyMap(), val walks: Set<UUID> = emptySet()) {
    /** Whether [man] still waits for the squad to decide: neither a seat nor told to walk. */
    fun undecided(man: UUID) = man !in seats && man !in walks

    companion object {
        val EMPTY = SeatPlan()
    }
}

/**
 * How a travelling squad shares out its ground vehicles' seats (#255): one decision for the squad,
 * where each man used to take the vehicle he found first, so a squad split over vehicles by search
 * order and a man left behind took a nearer stranger's vehicle over his squad's.
 *
 * - A man keeps the seat he was given while its vehicle is still there and not [refused][allocate]
 *   him: no reshuffle as men walk over.
 * - The rest fill the vehicles the squad already rides in first, then the roomiest, nearest the
 *   men first, so the squad keeps together in as few vehicles as fit it.
 * - In a vehicle nobody drives yet, the man given a seat nearest it takes the wheel.
 * - A man who finds no seat walks for the rest of the order: looking again every second froze a
 *   squad on a long march every half a minute.
 */
object SeatAllocation {
    /** How far from a man a vehicle may be to be given him: the distance he searched before. */
    const val REACH = 60.0

    /**
     * The seats for [riders] in [vehicles], keeping [previous]'s where it can. [refused] is, by man,
     * the vehicles he failed to board this order.
     */
    fun allocate(previous: SeatPlan, riders: List<SeatRider>, vehicles: List<SeatVehicle>, refused: Map<UUID, Set<UUID>> = emptyMap()): SeatPlan {
        val byId = vehicles.associateBy { it.id }
        val present = riders.mapTo(HashSet()) { it.id }
        val seats = previous.seats.filterTo(LinkedHashMap()) { (man, seat) -> man in present && seat.vehicle in byId && seat.vehicle !in refused[man].orEmpty() }
        val walks = previous.walks.filterTo(HashSet()) { it in present }
        val open = riders.filter { it.id !in seats && it.id !in walks }
        if (open.isEmpty()) return SeatPlan(seats, walks)
        val centre = Point3(open.sumOf { it.at.x } / open.size, open.sumOf { it.at.y } / open.size, open.sumOf { it.at.z } / open.size)
        val used = seats.values.mapTo(HashSet()) { it.vehicle }
        val order = vehicles.sortedWith(
            compareByDescending<SeatVehicle> { it.ours || it.id in used }
                .thenByDescending { it.free }
                .thenBy { flat(it.at, centre) },
        )
        val waiting = open.toMutableList()
        order.forEach { fill(it, seats, waiting, refused) }
        waiting.mapTo(walks) { it.id }
        return SeatPlan(seats, walks)
    }

    /** Gives the men in [waiting] nearest [vehicle] its seats still free, the first at the wheel if nobody has it. */
    private fun fill(vehicle: SeatVehicle, seats: MutableMap<UUID, RideSeat>, waiting: MutableList<SeatRider>, refused: Map<UUID, Set<UUID>>) {
        val room = vehicle.free - seats.values.count { it.vehicle == vehicle.id }
        if (room <= 0 || waiting.isEmpty()) return
        val taken = waiting.filter { flat(it.at, vehicle.at) <= REACH && vehicle.id !in refused[it.id].orEmpty() }
            .sortedBy { flat(it.at, vehicle.at) }
            .take(room)
        val wheelTaken = vehicle.driven || seats.values.any { it.vehicle == vehicle.id && it.driver }
        taken.forEachIndexed { index, man -> seats[man.id] = RideSeat(vehicle.id, driver = !wheelTaken && index == 0) }
        waiting.removeAll(taken)
    }

    private fun flat(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)
}
