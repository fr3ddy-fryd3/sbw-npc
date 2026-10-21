package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The last [RANGE] blocks of an attack before first contact (owner's proposal, #148): men on foot
 * walk instead of running and keep their eyes on the objective's sector, until the squad meets the
 * enemy. Then the attack goes on at combat pace, for the rest of that order.
 */
object WaryApproach {
    const val RANGE = 100.0

    /** Walking pace, as [com.sbwnpc.squad.entity.ai.SquadOrderBehaviour]'s walk. */
    private const val WALK_SPEED = 0.6

    /** How long each refresh keeps a man wary: a little over the refresh, so it lapses soon after it ends. */
    private const val WARY_TICKS = 40
    private const val REFRESH_TICKS = 20

    /** Per squad: when this order was first seen, whether it has met the enemy since, and today's answer. */
    internal class Latch {
        private var stamp = Int.MIN_VALUE
        var since = 0L
            private set
        var ended = false
            private set

        /** Notes order [orderStamp] at [now], and ends the approach for good once [contact] says so. */
        fun update(orderStamp: Int, now: Long, contact: (since: Long) -> Boolean): Boolean {
            if (orderStamp != stamp) {
                stamp = orderStamp
                since = now
                ended = false
            }
            if (!ended && contact(since)) ended = true
            return !ended
        }
    }

    private class Entry(val latch: Latch = Latch(), var tick: Long = Long.MIN_VALUE, var open: Boolean = false, var announced: Int = Int.MIN_VALUE)

    private val squads = HashMap<UUID, Entry>()

    fun clearAll() = squads.clear()

    /**
     * Whether [npc] is on a wary approach: his squad is attacking, has met no enemy since the
     * order, and he is on foot within [RANGE] of where the attack was sent.
     */
    fun applies(npc: NpcEntity): Boolean {
        val squad = npc.currentSquad() ?: return false
        if (squad.order != SquadOrder.ATTACK || npc.vehicle != null) return false
        val level = npc.level() as? ServerLevel ?: return false
        if (!open(level, squad)) return false
        val center = npc.orderCenter() ?: return false
        return npc.position().subtract(center).horizontalDistance() <= RANGE
    }

    /**
     * [run] off a wary approach, a walk on one; on one it also keeps [npc] watching the
     * objective's sector, refreshed once a second.
     */
    fun pace(npc: NpcEntity, run: Double): Double {
        if (!applies(npc)) return run
        val squad = npc.currentSquad()
        val entry = squad?.let { squads[it.id] }
        if (squad != null && entry != null && entry.announced != squad.orderStamp) {
            entry.announced = squad.orderStamp
            DebugFlags.log(LogGroup.ORDER, "[squad] {} event=wary_approach_start within={} blocks", squad.name, RANGE.toInt())
        }
        if (npc.tickCount % REFRESH_TICKS == 0) npc.orderCenter()?.let { npc.watchToward(it, WARY_TICKS) }
        return WALK_SPEED
    }

    /** Once per squad per tick: every man asks, and the answer is the squad's. */
    private fun open(level: ServerLevel, squad: Squad): Boolean {
        val now = level.gameTime
        val entry = squads.getOrPut(squad.id) { Entry() }
        if (entry.tick == now) return entry.open
        entry.tick = now
        val was = !entry.latch.ended
        entry.open = entry.latch.update(squad.orderStamp, now) { since -> metEnemy(level, squad, since, now) }
        if (was && !entry.open) DebugFlags.log(LogGroup.ORDER, "[squad] {} event=wary_approach_end order={} after={} ticks", squad.name, squad.orderStamp, now - entry.latch.since)
        return entry.open
    }

    /**
     * A contact seen since [since], a man with a target, or a man under fire. Not a sighting
     * another squad relayed: a fight far off is no reason to stop walking here, and a man who
     * takes that target counts as soon as he does.
     */
    private fun metEnemy(level: ServerLevel, squad: Squad, since: Long, now: Long): Boolean {
        if (squad.tactics.contacts.values.any { it.seenAt >= since }) return true
        return squad.members.any { id ->
            val man = level.getEntity(id) as? NpcEntity ?: return@any false
            man.target != null || man.incomingFire.pending(now)
        }
    }
}
