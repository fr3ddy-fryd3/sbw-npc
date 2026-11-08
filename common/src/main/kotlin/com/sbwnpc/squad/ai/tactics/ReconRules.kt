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

    /** Whether a man of a squad that [holds] its fire may still shoot at a target [distance] blocks away. */
    fun mayFire(holds: Boolean, distance: Double) = !holds || distance < CLOSE
}
