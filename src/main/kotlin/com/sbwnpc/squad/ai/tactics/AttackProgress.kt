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
    private var lastContactAt = now
    private var engagedSince: Long? = null
    private var lastIncomingAt: Long? = null
    private var peakEnemies = 0
    private var clearing: Set<UUID> = emptySet()

    /** Records one update's observations and advances [phase]; returns the inputs it decided on. */
    fun observe(now: Long, contactSeen: Boolean, incoming: Boolean, readyShooters: Int, enemiesAtObjective: Int, occupied: Boolean, threatsNearby: Int): AttackPhase.Inputs {
        if (contactSeen) {
            lastContactAt = now
            if (engagedSince == null) engagedSince = now
        }
        if (incoming) lastIncomingAt = now
        peakEnemies = max(peakEnemies, enemiesAtObjective)
        val inputs = AttackPhase.Inputs(
            readyShooters = readyShooters,
            enemiesAtObjective = enemiesAtObjective,
            peakEnemiesAtObjective = peakEnemies,
            ticksSinceContact = now - lastContactAt,
            ticksEngaged = engagedSince?.let { now - it } ?: 0,
            ticksSinceIncoming = now - (lastIncomingAt ?: engagedSince ?: now),
            occupied = occupied,
            threatsNearby = threatsNearby,
        )
        phase = phase.next(inputs)
        return inputs
    }

    /** The roles the current [phase] implies, keeping the members already sent clearing. */
    fun roles(objective: Point3, movers: List<UUID>, members: List<AttackMember>, threats: List<Point3>): AttackRoles = OperationTactics.roles(phase, objective, movers, members, threats, clearing).also { clearing = it.clearing.keys }
}
