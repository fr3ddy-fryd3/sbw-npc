package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.Diplomacy
import net.minecraft.world.phys.Vec3
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
 *  - **Freshness**: for fire support a contact stops being usable almost immediately once nobody
 *    has confirmed seeing it recently (~2s) — so the mortar never keeps firing at a target that
 *    broke line of sight into a building/trench. Infantry and the map remember it for
 *    [MEMORY_TICKS] instead.
 *
 * A contact nobody has laid eyes on — a killer, reported by the man he killed — only puts infantry
 * on alert ([reportUnseen]). Fire support works from [sightings] alone, and aims at where the enemy
 * was last seen, not at where he has got to since: a glimpse through the trees used to hand every
 * mortar and drone of the side his live position for as long as the contact stayed fresh.
 */
object TeamAwareness {

    private class Contact(val firstSeenTick: Long, var lastSeenTick: Long) {
        /** Set once someone has actually seen it — [NEVER] for a contact only known by its kills. */
        var firstSightedTick = NEVER
        var lastSightedTick = NEVER
        var lastSightedPos: Vec3 = Vec3.ZERO
        var sightedBy = ""
    }

    /** A contact fire support may act on: where it was last seen, when, and by whom. */
    class Sighting(val target: UUID, val pos: Vec3, val tick: Long, val by: String)

    private const val NEVER = Long.MIN_VALUE / 2

    const val ALERT_DELAY_TICKS = 60L   // ~3s before non-spotters can act on it
    const val RECENT_WINDOW_TICKS = 40L // ~2s since the last confirmed sighting by anyone
    /**
     * How long infantry and the map keep acting on a contact nobody is still looking at. The 2s
     * window above is for fire support, which must not shell a spot the enemy has left; used for
     * everyone it was shorter than the relay delay itself, so a contact reported once — a man's
     * killer, reported as he died — or seen for under 3s went stale before anyone else could
     * know of it, and an ally standing nearby never reacted.
     */
    const val MEMORY_TICKS = 200L       // ~10s
    const val FORGET_TICKS = 200L       // ~10s untouched -> drop entirely
    private const val SWEEP_INTERVAL_TICKS = 100L

    private val byFaction = HashMap<SquadFaction, MutableMap<UUID, Contact>>()
    private var lastSweepTick = -SWEEP_INTERVAL_TICKS

    /** Called by any unit of [faction] that currently has direct line of sight to [target];
     *  [by] names the spotter for the fire-support trace. */
    fun report(faction: SquadFaction, target: UUID, pos: Vec3, tick: Long, by: String) {
        val contact = touch(faction, target, tick)
        if (contact.firstSightedTick == NEVER) contact.firstSightedTick = tick
        contact.lastSightedTick = tick
        contact.lastSightedPos = pos
        contact.sightedBy = by
    }

    /** An enemy known to be about without anyone seeing him — whoever just killed one of ours.
     *  Infantry turn to meet him; fire support does not shoot at him on this alone. */
    fun reportUnseen(faction: SquadFaction, target: UUID, tick: Long) {
        touch(faction, target, tick)
    }

    private fun touch(faction: SquadFaction, target: UUID, tick: Long): Contact {
        val contacts = byFaction.getOrPut(faction) { HashMap() }
        val contact = contacts[target]?.also { it.lastSeenTick = tick } ?: Contact(tick, tick).also { contacts[target] = it }
        sweep(tick)
        return contact
    }

    /** What [faction] and its allies have seen within [RECENT_WINDOW_TICKS], past the relay delay —
     *  the targets fire support may use, at the spot each was last seen. Freshest first. */
    fun sightings(faction: SquadFaction, tick: Long): List<Sighting> {
        var result: MutableList<Sighting>? = null
        for (side in Diplomacy.alliesOf(faction)) {
            val contacts = byFaction[side] ?: continue
            for ((id, c) in contacts) {
                if (tick - c.lastSightedTick > RECENT_WINDOW_TICKS || tick - c.firstSightedTick < ALERT_DELAY_TICKS) continue
                val list = result ?: ArrayList<Sighting>(4).also { result = it }
                val at = list.indexOfFirst { it.target == id }
                if (at >= 0 && list[at].tick >= c.lastSightedTick) continue
                if (at >= 0) list.removeAt(at)
                list.add(Sighting(id, c.lastSightedPos, c.lastSightedTick, c.sightedBy))
            }
        }
        return result?.sortedByDescending { it.tick } ?: emptyList()
    }

    /** The latest sighting of [target] by [faction] or its allies within [window], with no relay
     *  delay — for a unit already shooting at it, keeping its aim on what the side can see. */
    fun lastSighting(faction: SquadFaction, target: UUID, tick: Long, window: Long = RECENT_WINDOW_TICKS): Sighting? {
        var best: Sighting? = null
        for (side in Diplomacy.alliesOf(faction)) {
            val c = byFaction[side]?.get(target) ?: continue
            if (tick - c.lastSightedTick > window) continue
            if (best == null || c.lastSightedTick > best.tick) best = Sighting(target, c.lastSightedPos, c.lastSightedTick, c.sightedBy)
        }
        return best
    }

    /** Contacts of [faction] that are fresh AND past the relay delay — actionable for a unit that
     *  is NOT the one currently seeing them. A caller with its own direct line of sight to a
     *  candidate should treat that candidate as actionable regardless of this list (no delay for
     *  whoever just found it themself) — this only covers what's been relayed from elsewhere. */
    fun relayedContacts(faction: SquadFaction, tick: Long, window: Long = RECENT_WINDOW_TICKS): List<UUID> {
        // Allies share what they see; an unallied faction is just itself.
        val sides = Diplomacy.alliesOf(faction)
        // Called per NPC per sensor scan — one pass, one (usually empty) list, not filter + map.
        var result: MutableList<UUID>? = null
        for (side in sides) {
            val contacts = byFaction[side] ?: continue
            for ((id, contact) in contacts) {
                if (tick - contact.lastSeenTick <= window && tick - contact.firstSeenTick >= ALERT_DELAY_TICKS) {
                    val list = result ?: ArrayList<UUID>(4).also { result = it }
                    if (id !in list) list.add(id)
                }
            }
        }
        return result ?: emptyList()
    }

    /** Everything [faction] and its allies have seen recently, with no relay delay — what a
     *  commander would have marked on the map. */
    fun knownContacts(faction: SquadFaction, tick: Long): Set<UUID> {
        val out = HashSet<UUID>()
        for (side in Diplomacy.alliesOf(faction)) {
            byFaction[side]?.forEach { (id, c) -> if (tick - c.lastSeenTick <= MEMORY_TICKS) out += id }
        }
        return out
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
