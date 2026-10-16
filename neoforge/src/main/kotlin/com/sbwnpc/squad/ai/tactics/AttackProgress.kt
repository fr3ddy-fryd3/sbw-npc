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
        return inputs
    }

    /** The roles the current [phase] implies, keeping the members already sent clearing. */
    fun roles(objective: Point3, movers: List<UUID>, members: List<AttackMember>, threats: List<Point3>): AttackRoles {
        if (phase == AttackPhase.ASSAULT) {
            members.filter { it.id in movers }.forEach { closingOrigins.putIfAbsent(it.id, it.position) }
        } else {
            closingOrigins.clear()
        }
        return OperationTactics.roles(phase, objective, movers, members, threats, clearing, closingOrigins).also { clearing = it.clearing.keys }
    }
}
