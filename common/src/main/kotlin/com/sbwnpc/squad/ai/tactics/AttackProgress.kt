package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID
import kotlin.math.max

/**
 * One operation's attack as observed over time: keeps the history [AttackPhase] decisions need
 * and the clearing team, so it stays stable between decisions. The caller only reports what it
 * sees each update; no world access.
 */
class AttackProgress(now: Long) {
    var phase = AttackPhase.DEPLOY
        private set

    // Unset until the first contact: an approach that has not met the defence yet is not a quiet
    // objective, and counting from the order made the attack assault an unseen defence.
    private var lastContactAt: Long? = null
    private var engagedSince: Long? = null
    private var lastIncomingAt: Long? = null
    private var peakEnemies = 0
    private var peakThreats = 0
    private var aborted = false
    private var clearing: Set<UUID> = emptySet()

    // Where each mover stood as this assault began: its closing post is measured from there, so
    // the post stays put while the man walks to it.
    private val closingOrigins = HashMap<UUID, Point3>()

    // Defenders seen at the objective and when, kept past the short contact memory: see [defenders].
    private val knownDefenders = HashMap<UUID, Pair<Point3, Long>>()

    private var lastInputs: AttackPhase.Inputs? = null

    // When half the movers first stood on their closing posts in this assault, see [roles].
    private var closedInSince: Long? = null

    /** The closed-in assault goes onto the point despite defenders still seen round it. */
    var occupying = false
        private set

    /**
     * The defenders at the objective this attack knows of: those [seen] now, and those seen within
     * [DEFENDER_MEMORY_TICKS] that are still [alive]. Returns their last known positions.
     *
     * Contacts last 10 s. In-game 17.10, 12 attackers against 16 defenders went to ASSAULT when
     * only 5 of them were in sight: the rest had stepped out of view, not died, and the "thinned"
     * defence beat the attack back with a third of it lost (#54).
     */
    fun defenders(now: Long, seen: Map<UUID, Point3>, alive: (UUID) -> Boolean): List<Point3> {
        seen.forEach { (id, position) -> knownDefenders[id] = position to now }
        knownDefenders.entries.removeIf { (id, known) -> now - known.second > DEFENDER_MEMORY_TICKS || !alive(id) }
        return knownDefenders.values.map { it.first }
    }

    /** Records one update's observations and advances [phase]; returns the inputs it decided on. */
    fun observe(now: Long, contactSeen: Boolean, incoming: Boolean, readyShooters: Int, enemiesAtObjective: Int, occupied: Boolean, threatsNearby: Int): AttackPhase.Inputs {
        if (contactSeen) {
            lastContactAt = now
            if (engagedSince == null) engagedSince = now
        }
        if (incoming) lastIncomingAt = now
        peakEnemies = max(peakEnemies, enemiesAtObjective)
        peakThreats = max(peakThreats, threatsNearby)
        val inputs = AttackPhase.Inputs(
            readyShooters = readyShooters,
            enemiesAtObjective = enemiesAtObjective,
            peakEnemiesAtObjective = peakEnemies,
            ticksSinceContact = lastContactAt?.let { now - it } ?: 0,
            ticksEngaged = engagedSince?.let { now - it } ?: 0,
            ticksSinceIncoming = now - (lastIncomingAt ?: engagedSince ?: now),
            occupied = occupied,
            threatsNearby = threatsNearby,
            aborted = aborted,
            peakThreatsNearby = peakThreats,
        )
        val next = phase.next(inputs)
        if (phase != AttackPhase.DEPLOY && next == AttackPhase.DEPLOY) aborted = true
        phase = next
        lastInputs = inputs
        return inputs
    }

    /**
     * The roles the current [phase] implies, keeping the members already sent clearing.
     *
     * A closed-in assault does not stop at its closing posts. Once half the movers have stood on
     * theirs for [CLOSED_IN_TICKS] with the attack still twice the known defenders, they go onto
     * the point, and taking it hands the rest to [AttackPhase.CLEAR]. Closing in alone ended
     * nothing: in-game 17.10 an assault stood 20 blocks from the defenders for a quarter of an
     * hour, reinforcements included, waiting for the last one near the point to fall (#54).
     */
    fun roles(now: Long, objective: Point3, movers: List<UUID>, members: List<AttackMember>, threats: List<Point3>): AttackRoles {
        if (phase != AttackPhase.ASSAULT) {
            closingOrigins.clear()
            closedInSince = null
            occupying = false
            return OperationTactics.roles(phase, objective, movers, members, threats, clearing).also { clearing = it.clearing.keys }
        }
        members.filter { it.id in movers }.forEach { closingOrigins.putIfAbsent(it.id, it.position) }
        if (!occupying) {
            val closing = OperationTactics.roles(phase, objective, movers, members, threats, clearing, closingOrigins).closing
            val positions = members.associate { it.id to it.position }
            val standing = closing.count { (id, post) -> positions[id]?.let { distance(it, post) <= CLOSED_IN_REACH } == true }
            if (closing.isNotEmpty() && standing * 2 >= closing.size && closedInSince == null) closedInSince = now
            val inputs = lastInputs
            occupying = closedInSince?.let { now - it >= CLOSED_IN_TICKS } == true && inputs != null &&
                inputs.readyShooters >= AttackPhase.ASSAULT_RATIO * inputs.enemiesAtObjective
        }
        // Going onto the point, the defenders still round it no longer hold the movers back.
        val holding = if (occupying) threats.filterNot { OperationTactics.atObjective(it, objective) } else threats
        return OperationTactics.roles(phase, objective, movers, members, holding, clearing, closingOrigins).also { clearing = it.clearing.keys }
    }

    private fun distance(a: Point3, b: Point3) = kotlin.math.hypot(a.x - b.x, a.z - b.z)

    companion object {
        /** A defender unseen this long (30 s) and not known dead is no longer counted. */
        const val DEFENDER_MEMORY_TICKS = 600L

        /** Half the movers on their closing posts this long (20 s) before going onto the point. */
        const val CLOSED_IN_TICKS = 400L

        /** A mover this near its closing post stands on it. */
        const val CLOSED_IN_REACH = 6.0
    }
}
