package com.sbwnpc.squad.combat

import java.util.UUID

/**
 * What a man's gun saw of his fight on its last checks: when he last had a clear shot, whether
 * his line of fire is blocked by an ally or a hull and for how many checks in a row, and how far
 * he got with his opening fire on his current target.
 *
 * Perception, not a decision: his gun behaviour is its only writer and his plan's facts its only
 * reader, so the plan chooses how he moves on what the gun saw, as with [IncomingFire].
 */
class FireObservation {
    /** Game tick of his last clear shot at his target, or [NEVER]. */
    var clearShotAt = NEVER
        private set

    /** Lane checks in a row that found an ally in his line of fire. */
    var allyBlockedChecks = 0
        private set

    /** Lane checks in a row that found a vehicle hull in his line of fire. */
    var hullBlockedChecks = 0
        private set

    /** The target his opening fire counts are for. */
    var target: UUID? = null
        private set

    /** Game tick he first had a shootable line to [target], or null before it. */
    var firstShootableAt: Long? = null
        private set

    /** Shots he fired at [target]. */
    var shots = 0
        private set

    /** His weapon fires automatic bursts: an opening burst is more than one round. */
    var automatic = false
        private set

    private var orderStamp: Int? = null

    /**
     * One look at [targetId] at [now]: [shootable] when he could shoot it. A new target, or a new
     * order of his squad ([stamp]), starts his opening fire over.
     */
    fun look(targetId: UUID, now: Long, shootable: Boolean, automaticWeapon: Boolean, stamp: Int?) {
        if (targetId != target || stamp != orderStamp) {
            target = targetId
            orderStamp = stamp
            firstShootableAt = null
            shots = 0
        }
        automatic = automaticWeapon
        if (!shootable) return
        clearShotAt = now
        if (firstShootableAt == null) firstShootableAt = now
    }

    /** One lane check: [allyClear] when no ally stands in it, [hullBlocked] when a hull does. */
    fun lane(allyClear: Boolean, hullBlocked: Boolean) {
        allyBlockedChecks = if (allyClear || hullBlocked) 0 else allyBlockedChecks + 1
        hullBlockedChecks = if (hullBlocked) hullBlockedChecks + 1 else 0
    }

    /** He fired a round at his target. */
    fun fired() {
        shots++
    }

    /** The fight is over: nothing he saw in it carries into the next one. */
    fun reset() {
        clearShotAt = NEVER
        allyBlockedChecks = 0
        hullBlockedChecks = 0
        target = null
        firstShootableAt = null
        shots = 0
        automatic = false
        orderStamp = null
    }

    companion object {
        /** No clear shot seen yet. */
        const val NEVER = Long.MIN_VALUE / 2
    }
}
