package com.sbwnpc.squad.combat

/**
 * What a squad already knows (#190, variant A): one contact per squad. Once one man has noticed an
 * enemy, his squadmates do not spend a spotting time on him again, see [Noticing.noticed].
 */
object SquadKnowledge {
    /** How long a squad's contact on an enemy stays good, the same ten seconds its tactical picture keeps. */
    const val KNOWN_TICKS = 200L

    /** Whether a contact last [seenAt] (null: none) still counts as known at [now]. */
    fun known(seenAt: Long?, now: Long): Boolean = seenAt != null && now - seenAt <= KNOWN_TICKS
}
