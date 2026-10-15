package com.sbwnpc.squad.ai.tactics

/**
 * What an attacking operation is doing at its objective: building its firing line, assaulting
 * into the objective, or clearing enemies left around it once it is taken.
 *
 * Pure decision over observed counts; [AttackProgress] keeps the history between decisions.
 */
enum class AttackPhase {
    DEPLOY,
    ASSAULT,
    CLEAR,
    ;

    /**
     * Observations for one decision. [enemiesAtObjective] and [threatsNearby] are seen, live
     * contacts; [peakEnemiesAtObjective] is the most seen at the objective during this attack.
     */
    data class Inputs(
        val readyShooters: Int,
        val enemiesAtObjective: Int,
        val peakEnemiesAtObjective: Int,
        val ticksSinceContact: Long,
        val ticksEngaged: Long,
        val ticksSinceIncoming: Long,
        val occupied: Boolean,
        val threatsNearby: Int,
    )

    fun next(inputs: Inputs): AttackPhase {
        if (inputs.occupied) return if (inputs.threatsNearby > 0) CLEAR else ASSAULT
        val quiet = inputs.ticksSinceContact >= QUIET_TICKS
        return when (this) {
            DEPLOY -> if (quiet || (superior(inputs) && suppressed(inputs))) ASSAULT else DEPLOY

            // Lost ground before the objective is taken: back to the firing line rather than
            // walking the assault group into a fight it no longer wins.
            ASSAULT, CLEAR -> if (!quiet && inputs.readyShooters < ABORT_RATIO * inputs.enemiesAtObjective) DEPLOY else ASSAULT
        }
    }

    companion object {
        /** Ready shooters per enemy at the objective needed to start an assault. */
        const val ASSAULT_RATIO = 2.0

        /** Below this many ready shooters per enemy an assault is called off; lower than
         *  [ASSAULT_RATIO] so one casualty does not flip the phase back and forth. */
        const val ABORT_RATIO = 1.5

        /** No enemy seen for this long (10 s) also starts the assault. */
        const val QUIET_TICKS = 200L

        /** The firing line works for at least this long (20 s) before an assault. */
        const val ENGAGE_TICKS = 400L

        /** No fire on the attackers for this long (5 s) counts as a suppressed defence. */
        const val SILENT_TICKS = 100L

        /** Enemies this close to the objective count as holding it. */
        const val OBJECTIVE_RADIUS = 48.0

        private fun superior(inputs: Inputs) = inputs.readyShooters >= ASSAULT_RATIO * inputs.enemiesAtObjective

        // A fresh force facing an untouched defence is not yet winning: the firing line first
        // has to thin it out or silence it.
        private fun suppressed(inputs: Inputs) =
            inputs.ticksEngaged >= ENGAGE_TICKS &&
                (inputs.enemiesAtObjective * 2 <= inputs.peakEnemiesAtObjective || inputs.ticksSinceIncoming >= SILENT_TICKS)
    }
}
