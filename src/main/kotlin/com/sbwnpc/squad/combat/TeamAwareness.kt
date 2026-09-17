package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.SquadFaction
import java.util.UUID

/**
 * Runtime-only (never persisted — rebuilt from scratch each load, nothing here is worth saving):
 * "who does faction X currently know is out there, and since when."
 *
 * Any combat unit with a real, direct line of sight to a hostile reports it here — the sighting
 * becomes visible to every ally in the same FACTION, not just the reporter's own squad (this is
 * deliberately broader than squad scope: a dedicated mortar crew squad has no infantry of its own
 * to ever see anything, so squad-only sharing would have left it permanently blind). Two rules
 * keep this honest instead of just another blind radius scan with extra steps:
 *  - **Relay delay**: a contact only becomes usable by OTHER units ~3s after it was first spotted
 *    (simulates a radio call reaching the rest of the faction). The spotter itself needs no delay —
 *    that's the caller's own responsibility to handle (see [relayedContacts] doc).
 *  - **Freshness**: a contact stops being usable almost immediately once nobody has confirmed
 *    seeing it recently (~2s) — so units relying on this (chiefly the mortar) never keep firing at
 *    a target that broke line of sight into a building/trench, even though the target entity is
 *    technically still alive and in range.
 */
object TeamAwareness {

    private class Contact(val firstSeenTick: Long, var lastSeenTick: Long)

    const val ALERT_DELAY_TICKS = 60L   // ~3s before non-spotters can act on it
    const val RECENT_WINDOW_TICKS = 40L // ~2s since the last confirmed sighting by anyone
    const val FORGET_TICKS = 200L       // ~10s untouched -> drop entirely
    private const val SWEEP_INTERVAL_TICKS = 100L

    private val byFaction = HashMap<SquadFaction, MutableMap<UUID, Contact>>()
    private var lastSweepTick = -SWEEP_INTERVAL_TICKS

    /** Called by any unit of [faction] that currently has direct line of sight to [target]. */
    fun report(faction: SquadFaction, target: UUID, tick: Long) {
        val contacts = byFaction.getOrPut(faction) { HashMap() }
        val existing = contacts[target]
        if (existing == null) {
            contacts[target] = Contact(tick, tick)
        } else {
            existing.lastSeenTick = tick
        }
        sweep(tick)
    }

    /** Contacts of [faction] that are fresh AND past the relay delay — actionable for a unit that
     *  is NOT the one currently seeing them. A caller with its own direct line of sight to a
     *  candidate should treat that candidate as actionable regardless of this list (no delay for
     *  whoever just found it themself) — this only covers what's been relayed from elsewhere. */
    fun relayedContacts(faction: SquadFaction, tick: Long): List<UUID> {
        val contacts = byFaction[faction] ?: return emptyList()
        if (contacts.isEmpty()) return emptyList()
        // Called per NPC per sensor scan — one pass, one (usually empty) list, not filter + map.
        var result: MutableList<UUID>? = null
        for ((id, contact) in contacts) {
            if (tick - contact.lastSeenTick <= RECENT_WINDOW_TICKS && tick - contact.firstSeenTick >= ALERT_DELAY_TICKS) {
                (result ?: ArrayList<UUID>(4).also { result = it }).add(id)
            }
        }
        return result ?: emptyList()
    }

    /** [tick] must be ServerLevel.gameTime: entity tick counts are not comparable across NPCs. */
    private fun sweep(tick: Long) {
        if (tick - lastSweepTick < SWEEP_INTERVAL_TICKS) return
        lastSweepTick = tick
        byFaction.values.forEach { it.entries.removeIf { contact -> tick - contact.value.lastSeenTick > FORGET_TICKS } }
        byFaction.entries.removeIf { it.value.isEmpty() }
    }

    fun clearAll() {
        byFaction.clear()
        lastSweepTick = -SWEEP_INTERVAL_TICKS
    }
}
