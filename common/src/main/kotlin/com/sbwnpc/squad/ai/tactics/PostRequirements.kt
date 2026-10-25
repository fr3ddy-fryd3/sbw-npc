package com.sbwnpc.squad.ai.tactics

/**
 * What a member's post must give him while his squad is under fire.
 *
 * Only incoming fire asks for either: off fire, a post is chosen for its line and reach alone.
 */
object PostRequirements {
    /** A man running under fire must end his run in cover. */
    fun cover(duty: CombatDuty, incoming: Boolean): Boolean = incoming && duty.coverUnderFire

    /** A man moving or firing under fire needs shelter from the bearing the fire comes from. */
    fun shelter(duty: CombatDuty, incoming: Boolean): Boolean = incoming && duty.shelterUnderFire
}
