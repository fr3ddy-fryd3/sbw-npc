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

    // When half the movers first stood on their closing posts in this assault, and when the
    // assault began; see [roles].
    private var closedInSince: Long? = null
    private var assaultSince: Long? = null

    // Each bounding man's current post, when he got it and when he first stood on it; see [bounds].
    private val boundPosts = HashMap<UUID, Point3>()
    private val boundIssued = HashMap<UUID, Long>()
    private val boundReached = HashMap<UUID, Long>()
    private var bounding = false

    // When the attack last went (back) to its firing line.
    private var deployedAt = now

    /** The members on a bound, see [bounds]: they close on the defenders and keep off the arc. */
    val bounders: Set<UUID> get() = boundPosts.keys.toSet()

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
    fun observe(now: Long, contactSeen: Boolean, incoming: Boolean, readyShooters: Int, enemiesAtObjective: Int, occupied: Boolean, threatsNearby: Int, sectorsHeld: Boolean = true): AttackPhase.Inputs {
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
            sectorsHeld = sectorsHeld,
        )
        val next = phase.next(inputs)
        if (phase != AttackPhase.DEPLOY && next == AttackPhase.DEPLOY) {
            aborted = true
            deployedAt = now
        }
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
            assaultSince = null
            occupying = false
            return OperationTactics.roles(phase, objective, movers, members, threats, clearing).also { clearing = it.clearing.keys }
        }
        members.filter { it.id in movers }.forEach { closingOrigins.putIfAbsent(it.id, it.position) }
        if (!occupying) occupying = closedIn(now, objective, movers, members, threats)
        // Going onto the point, the defenders still round it no longer hold the movers back.
        val holding = if (occupying) threats.filterNot { OperationTactics.atObjective(it, objective) } else threats
        return OperationTactics.roles(phase, objective, movers, members, holding, clearing, closingOrigins).also { clearing = it.clearing.keys }
    }

    /** Whether the closed-in assault is due to go onto the point now; starts its clocks on the way. */
    private fun closedIn(now: Long, objective: Point3, movers: List<UUID>, members: List<AttackMember>, threats: List<Point3>): Boolean {
        val closing = OperationTactics.roles(phase, objective, movers, members, threats, clearing, closingOrigins).closing
        val positions = members.associate { it.id to it.position }
        val standing = closing.count { (id, post) -> positions[id]?.let { distance(it, post) <= CLOSED_IN_REACH } == true }
        if (closing.isNotEmpty() && standing * 2 >= closing.size && closedInSince == null) closedInSince = now
        val since = assaultSince ?: now.also { assaultSince = it }
        // Whatever keeps the movers off their closing posts, an assault that still has the
        // numbers does not stand there for good.
        val due = closedInSince?.let { now - it >= CLOSED_IN_TICKS } == true || now - since >= ASSAULT_LIMIT_TICKS
        val inputs = lastInputs ?: return false
        return due && inputs.readyShooters >= AttackPhase.ASSAULT_RATIO * inputs.enemiesAtObjective
    }

    /**
     * The closing post of each ready one of [bounders] while the attack deploys; empty in the other
     * phases. Once the firing line has worked [AttackPhase.ENGAGE_TICKS] with the flanks in their
     * sectors, each takes a post halfway to the nearest defender, no nearer than [BOUND_DISTANCE].
     * He takes the next one from where he stands after standing on it for [BOUND_PAUSE_TICKS], or
     * after [BOUND_LIMIT_TICKS] if he never gets there. Each man keeps his own clock, so the group
     * does not all run at once. Back on the line after an assault was called off, the line works
     * its time again first: the bounds do not go straight back in.
     *
     * Without the numbers to assault, a line 56 blocks out barely hit anything: in-game 17.10 a
     * squad of 11 against 14 stood there for good and seemed to do nothing (#65).
     */
    fun bounds(now: Long, objective: Point3, bounders: Set<UUID>, members: List<AttackMember>, threats: List<Point3>): Map<UUID, Point3> {
        val inputs = lastInputs
        if (phase != AttackPhase.DEPLOY) {
            bounding = false
            boundPosts.clear()
            boundIssued.clear()
            boundReached.clear()
            return emptyMap()
        }
        if (!bounding) bounding = inputs != null && inputs.ticksEngaged >= AttackPhase.ENGAGE_TICKS && now - deployedAt >= AttackPhase.ENGAGE_TICKS && inputs.sectorsHeld
        boundPosts.keys.retainAll(bounders)
        boundIssued.keys.retainAll(bounders)
        boundReached.keys.retainAll(bounders)
        if (!bounding) return emptyMap()
        val ready = members.filter { it.ready && it.id in bounders }
        val defenders = threats.filter { OperationTactics.atObjective(it, objective) }
        // With no defender known for a moment, the posts already taken stay: dropping them would
        // send the men back to the line and out again.
        if (defenders.isNotEmpty()) ready.forEach { bound(now, it, defenders) }
        return ready.mapNotNull { member -> boundPosts[member.id]?.let { member.id to it } }.toMap()
    }

    /** Gives [member] his first bound, or the next one once he has fired from his post long enough or never got there. */
    private fun bound(now: Long, member: AttackMember, defenders: List<Point3>) {
        val post = boundPosts[member.id]
        if (post != null) {
            if (distance(member.position, post) <= OperationTactics.MOVE_ARRIVAL) boundReached.putIfAbsent(member.id, now)
            val rested = boundReached[member.id]?.let { now - it >= BOUND_PAUSE_TICKS } == true
            val stuck = now - boundIssued.getValue(member.id) >= BOUND_LIMIT_TICKS
            if (!rested && !stuck) return
        }
        boundPosts[member.id] = OperationTactics.boundPost(member.position, defenders, BOUND_DISTANCE)
        boundIssued[member.id] = now
        boundReached.remove(member.id)
    }

    private fun distance(a: Point3, b: Point3) = kotlin.math.hypot(a.x - b.x, a.z - b.z)

    companion object {
        /** A defender unseen this long (30 s) and not known dead is no longer counted. */
        const val DEFENDER_MEMORY_TICKS = 600L

        /** Half the movers on their closing posts this long (20 s) before going onto the point. */
        const val CLOSED_IN_TICKS = 400L

        /** A mover this near its closing post stands on it. */
        const val CLOSED_IN_REACH = 6.0

        /** An assault with the numbers goes onto the point after this long (60 s) in any case. */
        const val ASSAULT_LIMIT_TICKS = 1200L

        /** A bound stops this far from the nearest defender: nearer than the line, short of an assault. */
        const val BOUND_DISTANCE = 30.0

        /** A bounding man fires from each post this long (10 s) before the next bound. */
        const val BOUND_PAUSE_TICKS = 200L

        /** A bounding man who has not reached his post in this long (30 s) takes a new one from where he is. */
        const val BOUND_LIMIT_TICKS = 600L
    }
}
