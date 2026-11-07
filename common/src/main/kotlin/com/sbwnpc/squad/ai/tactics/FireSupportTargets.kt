package com.sbwnpc.squad.ai.tactics

import java.util.UUID

/** Why a mortar crew fires where it does, the reason the decision trace shows (#258). */
enum class FireSource {
    /** A BARRAGE order: the area round its objective. */
    ORDER_BARRAGE,

    /** An ATTACK order while the infantry approaches: the area round the objective until it arrives. */
    ATTACK_PREPARATION,

    /** An ATTACK order's target: the squad's focus, else its objective point. */
    COMMANDED,

    /** An enemy the crew noticed itself. */
    OWN_SIGHTING,

    /** An enemy the rest of the faction reported. */
    FACTION_SIGHTING,
}

/**
 * What a crew fires at: [source] and a stable [key], the target's UUID or the order's objective.
 * Not a point: a target moving, or a barrage walking its aim round the area, must not change the
 * plan's fact (#251 review: facts change as often as the decision does, not every scan).
 */
data class FireTarget(val source: FireSource, val key: String) {
    val sighting get() = source == FireSource.OWN_SIGHTING || source == FireSource.FACTION_SIGHTING
}

/**
 * What a crew could fire at this scan, each null when absent. [orderKey] names the squad's current
 * order (squad and order stamp), so a new order is a new target.
 */
data class FireOptions(
    val barrage: Boolean = false,
    val preparation: Boolean = false,
    val commanded: String? = null,
    val ownSighting: UUID? = null,
    val factionSighting: UUID? = null,
    val orderKey: String = "",
    /** The keys of every enemy the crew or its faction still sees, so a target still in view is kept. */
    val sighted: Set<String> = emptySet(),
)

/**
 * Chooses a mortar crew's target in today's priority (owner, 01.11): a BARRAGE order, an ATTACK
 * while its infantry approaches, the ATTACK order's target, the crew's own sighting, the faction's
 * freshest sighting. Pure, so the plan's fact is decided here and the executor only resolves
 * where the chosen target stands now.
 */
object FireSupportTargets {
    /**
     * The target for [options], keeping [previous] while a sighting wins and its enemy is still
     * [FireOptions.sighted]: the same enemy known another way, or another enemy reported fresher
     * or standing a little nearer, must not switch the crew's fire and replan it every scan.
     */
    fun choose(options: FireOptions, previous: FireTarget?): FireTarget? {
        val chosen = when {
            options.barrage -> FireTarget(FireSource.ORDER_BARRAGE, options.orderKey)
            options.preparation -> FireTarget(FireSource.ATTACK_PREPARATION, options.orderKey)
            options.commanded != null -> FireTarget(FireSource.COMMANDED, options.commanded)
            options.ownSighting != null -> FireTarget(FireSource.OWN_SIGHTING, options.ownSighting.toString())
            options.factionSighting != null -> FireTarget(FireSource.FACTION_SIGHTING, options.factionSighting.toString())
            else -> null
        }
        return if (keeps(previous, chosen, options.sighted)) previous else chosen
    }

    private fun keeps(previous: FireTarget?, chosen: FireTarget?, sighted: Set<String>) = previous != null && chosen != null && previous.sighting && chosen.sighting && previous.key in sighted

    /** A crew rescans for enemies this often, as the operator did before (#258). */
    const val SCAN_INTERVAL_TICKS = 20

    /**
     * Its own sighting counts this long after the scan that last saw it: two scans, so one scan
     * that misses a target behind a tree does not hand the crew to a faction report of another.
     */
    const val OWN_SIGHTING_HOLD_TICKS = 2L * SCAN_INTERVAL_TICKS
}
