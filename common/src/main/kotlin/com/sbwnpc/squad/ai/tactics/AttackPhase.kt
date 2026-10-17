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
     * [ticksSinceContact] stays 0 until the attack first sees an enemy. [aborted] is set once
     * an assault of this attack has been called off; [peakThreatsNearby] is the most threats
     * seen round the objective during this attack.
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
        val aborted: Boolean = false,
        val peakThreatsNearby: Int = threatsNearby,
    )

    fun next(inputs: Inputs): AttackPhase {
        val quiet = inputs.ticksSinceContact >= QUIET_TICKS
        val losing = !quiet && inputs.readyShooters < ABORT_RATIO * inputs.enemiesAtObjective
        // Standing on the point does not win it against a defence that still outnumbers them.
        if (inputs.occupied && !losing) return if (inputs.threatsNearby > 0) CLEAR else ASSAULT
        return when (this) {
            DEPLOY -> if (quiet || (superior(inputs) && suppressed(inputs)) || overwhelming(inputs)) ASSAULT else DEPLOY

            // Lost ground before the objective is taken: back to the firing line rather than
            // walking the assault group into a fight it no longer wins.
            ASSAULT, CLEAR -> when {
                losing -> DEPLOY

                // Clearing sends men off the objective, so it alone can drop the holders below
                // occupation; going back to ASSAULT then recalled them and flipped every update.
                this == CLEAR && inputs.threatsNearby > 0 -> CLEAR

                else -> ASSAULT
            }
        }
    }

    companion object {
        /** Ready shooters per enemy at the objective needed to start an assault. */
        const val ASSAULT_RATIO = 2.0

        /** Below this many ready shooters per enemy an assault is called off; lower than
         *  [ASSAULT_RATIO] so one casualty does not flip the phase back and forth. */
        const val ABORT_RATIO = 1.5

        /** No enemy seen for this long (10 s) after a contact also starts the assault. */
        const val QUIET_TICKS = 200L

        /** The firing line works for at least this long (20 s) before an assault. */
        const val ENGAGE_TICKS = 400L

        /** No fire on the attackers for this long (15 s) counts as a suppressed defence. Five
         *  seconds were a pause between bursts, and the assault ran into a defence that only then
         *  showed its full size. */
        const val SILENT_TICKS = 300L

        /** Enemies this close to the objective count as holding it. */
        const val OBJECTIVE_RADIUS = 48.0

        /** Ready shooters per known defender that start an assault without the defence thinning. */
        const val OVERWHELMING_RATIO = 3.0

        /** How long (40 s) the firing line works before overwhelming numbers alone start the assault. */
        const val OVERWHELMING_ENGAGE_TICKS = 2 * ENGAGE_TICKS

        private fun superior(inputs: Inputs) = inputs.readyShooters >= ASSAULT_RATIO * inputs.enemiesAtObjective

        // A defence counted from memory thins only as its men die, and sixteen men under fire never
        // fall silent: in-game 17.10, 48 attackers stood off 16 defenders and never assaulted (#54).
        private fun overwhelming(inputs: Inputs) = inputs.ticksEngaged >= OVERWHELMING_ENGAGE_TICKS && inputs.readyShooters >= OVERWHELMING_RATIO * inputs.enemiesAtObjective

        // A fresh force facing an untouched defence is not yet winning: the firing line first
        // has to thin it out or silence it. After a called-off assault silence no longer counts:
        // the defence that drove it back has shown its size and has to be thinned.
        private fun suppressed(inputs: Inputs) =
            inputs.ticksEngaged >= ENGAGE_TICKS &&
                (thinned(inputs) || (!inputs.aborted && inputs.ticksSinceIncoming >= SILENT_TICKS))

        // Thinned round the objective as well as at it: defenders stepping back past
        // [OBJECTIVE_RADIUS] halved the count at the point while all of them were still there.
        private fun thinned(inputs: Inputs) = inputs.enemiesAtObjective * 2 <= inputs.peakEnemiesAtObjective && inputs.threatsNearby * 2 <= inputs.peakThreatsNearby
    }
}
