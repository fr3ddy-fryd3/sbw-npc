package com.sbwnpc.squad.combat

/**
 * What a squad already knows (#190, variant A): one contact per squad. Once one man has noticed an
 * enemy and told the others, they do not spend a spotting time on him again, see [Noticing.noticed].
 */
object SquadKnowledge {
    /** How long a squad's contact on an enemy stays good, the same ten seconds its tactical picture keeps. */
    const val KNOWN_TICKS = 200L

    /** The call takes 1.5 s: a second to say "nine o'clock" and half a second for everyone to take it in. */
    const val SHARE_TICKS = 30L

    /**
     * Whether a contact last seen at [seenAt] (null: none), first seen at [firstSeenAt], is one the
     * squad can act on at [now]: told long enough ago, and still fresh.
     */
    fun known(seenAt: Long?, firstSeenAt: Long?, now: Long): Boolean = seenAt != null && firstSeenAt != null && now - seenAt <= KNOWN_TICKS && now - firstSeenAt >= SHARE_TICKS

    /** When the call on an enemy started: kept while the contact stays fresh, restarted once it has lapsed. */
    fun firstSeenAt(oldSeenAt: Long?, oldFirstSeenAt: Long?, now: Long): Long = if (oldSeenAt != null && oldFirstSeenAt != null && now - oldSeenAt <= KNOWN_TICKS) oldFirstSeenAt else now
}
