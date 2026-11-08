package com.sbwnpc.squad.ai.tactics

/**
 * When a recon squad may fire (#194, owner 24.10 and 26.10): only once it is discovered, or when an
 * enemy is closer than [CLOSE] blocks. A recon squad that is alone in its operation, ordered to
 * attack, and has seen an enemy attacks it as any squad would; one whose operation holds infantry
 * goes into the fight with it once it has reported.
 *
 * Discovered, a recon squad with infantry beside it fights with it; any other leaves at once
 * ([retreats]).
 *
 * The decision is here; the trigger behaviours only read it, as an authority rule.
 */
object ReconRules {
    /** Who else is in the recon squad's operation. */
    enum class Company {
        /** No other squad. */
        ALONE,

        /** At least one squad that is not recon, mortars or drone operators. */
        INFANTRY,

        /** Only other recon, mortar or drone squads. */
        SUPPORT,
    }

    /** An enemy this close is answered whatever the order: hiding no longer works. */
    const val CLOSE = 5.0

    /**
     * Whether a recon squad holds its fire. [discovered] is the owner's sense: shot at, or targeted by
     * an enemy; shots near it alone do not say it was seen.
     */
    fun holdsFire(company: Company, attack: Boolean, sawEnemy: Boolean, discovered: Boolean, reported: Boolean = false): Boolean =
        when {
            company == Company.ALONE && attack && sawEnemy -> false
            company == Company.INFANTRY && reported -> false
            discovered -> false
            else -> true
        }

    /** Whether a recon squad just discovered withdraws (owner decision 3, 24.10) rather than fights. */
    fun retreats(company: Company, attack: Boolean, sawEnemy: Boolean): Boolean = company != Company.INFANTRY && !(company == Company.ALONE && attack && sawEnemy)

    /**
     * How long (60 s, owner 26.10) the operation waits for the report once its recon squad is at its
     * post. Counted from the order, it ran out while the scouts were still on their way: in the
     * playtest of 08.11 they needed two minutes to reach the post and the infantry went in first.
     */
    const val INTEL_WAIT_TICKS = 1200L

    /** However far the post, the operation never waits longer than this (5 min) for its recon. */
    const val INTEL_CAP_TICKS = 6000L

    /**
     * Whether an operation with a recon squad still waits for intelligence: its infantry walks and
     * stays short of its firing line, its mortars and drones hold their fire. A report, the recon
     * squad found or gone, [INTEL_WAIT_TICKS] at the post ([sincePost], null before it gets there) or
     * [INTEL_CAP_TICKS] since the order ends it.
     */
    fun awaitsIntel(sinceOrder: Long, sincePost: Long?, reconHidden: Boolean, reported: Boolean): Boolean = reconHidden && !reported && sinceOrder < INTEL_CAP_TICKS && (sincePost ?: 0L) < INTEL_WAIT_TICKS

    /** Water at least this many blocks long on the way is water to cross; a puddle is not. */
    const val CROSSING_MIN = 4

    /**
     * Whether the way marked block by block in [wet] crosses [CROSSING_MIN] or more wet blocks in a row:
     * then the recon stages and the wait for its report keep off (owner 08.11), see WaterCrossings.
     */
    fun waterBetween(wet: List<Boolean>): Boolean {
        var run = 0
        for (w in wet) {
            run = if (w) run + 1 else 0
            if (run >= CROSSING_MIN) return true
        }
        return false
    }

    /** Whether a man of a squad that [holds] its fire may still shoot at a target [distance] blocks away. */
    fun mayFire(holds: Boolean, distance: Double) = !holds || distance < CLOSE
}
