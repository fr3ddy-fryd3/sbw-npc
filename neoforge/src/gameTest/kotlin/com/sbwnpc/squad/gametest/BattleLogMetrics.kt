package com.sbwnpc.squad.gametest

import java.util.UUID

/**
 * The trace-log figures a playtest is read for, counted over one battle's attackers: why their
 * shots were held, how often movers gave up a post for the next one, and how often a post search
 * found nothing. Reads the same `[htn]` and `[tactics]` lines a human reads in the game log.
 */
class BattleLogMetrics(private val squads: Set<UUID>, private val actors: Set<UUID>) {
    val fireGates = HashMap<String, Int>()
    var intermediateReached = 0
        private set
    var exhaustedSearches = 0
        private set

    fun accept(line: String) {
        if (" event=fire_gate " in line) {
            val squad = SQUAD.find(line)?.groupValues?.get(1) ?: return
            if (UUID.fromString(squad) !in squads) return
            REASON.find(line)?.groupValues?.get(1)?.let { fireGates.merge(it, 1, Int::plus) }
            return
        }
        val actor = ACTOR.find(line)?.groupValues?.get(1)?.let(UUID::fromString) ?: return
        if (actor !in actors) return
        if (" event=post_invalidated reason=intermediate_reached" in line) intermediateReached++
        if (" event=action_failed reason=local_position_query_exhausted" in line) exhaustedSearches++
    }

    fun share(reason: String): Double = fireGates.values.sum().takeIf { it > 0 }?.let { (fireGates[reason] ?: 0).toDouble() / it } ?: 0.0

    private companion object {
        val SQUAD = Regex(""" squad=([0-9a-f-]{36}) """)
        val REASON = Regex(""" reason=([A-Z_]+)""")
        val ACTOR = Regex("""\[htn] actor=([0-9a-f-]{36}) """)
    }
}
