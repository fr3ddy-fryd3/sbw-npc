package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Radio calls from a squad to its commander — the player who owns it, in chat, only while online.
 *
 * Each kind of call has its own quiet interval per squad. Contact is called once per fight and
 * area: while a commander's squad is in contact, his other squads within [CONTACT_AREA] keep quiet
 * about it. Losses are called at two marks only — half the squad gone, and all of it. Runtime only:
 * calls missed while offline are gone. A player can switch them all off ([ReportMutes]).
 */
object SquadReports {

    private enum class Kind(val quietTicks: Long, val color: ChatFormatting) {
        CONTACT(0, ChatFormatting.RED),
        CASUALTIES(0, ChatFormatting.GOLD),
        DESTROYED(0, ChatFormatting.DARK_RED),
        LOW_AMMO(1200, ChatFormatting.YELLOW),
        RESUPPLY(600, ChatFormatting.GRAY),
        ARRIVED(0, ChatFormatting.GREEN),
    }

    private val lastSent = HashMap<Pair<UUID, Kind>, Long>()
    /** The order stamp a MOVE arrival was last called in for, per squad. */
    private val arrivalCalled = HashMap<UUID, Int>()

    /** A fight a commander has been told of: where, and when anyone last saw the enemy there. */
    private class Fight(val at: Vec3, var lastSeen: Long)
    private val fights = HashMap<UUID, MutableList<Fight>>()

    /** Most men each squad has had — what "half" is half of. */
    private val strength = HashMap<UUID, Int>()
    /** Squads whose half-strength call has gone out and that haven't been topped back up since. */
    private val halfCalled = HashSet<UUID>()

    private const val CHECK_TICKS = 100
    private const val LOW_AMMO_FRACTION = 0.3
    /** Squads of one commander this near an ongoing fight don't call it in again. */
    private const val CONTACT_AREA = 100.0
    /** A fight nobody has seen the enemy in for this long is over; the next sighting is news. */
    private const val CONTACT_QUIET_TICKS = 600L

    private fun send(server: MinecraftServer, squad: Squad, kind: Kind, text: String) {
        val owner = server.playerList.getPlayer(squad.owner) ?: return
        if (ReportMutes.get(server).isMuted(owner.uuid)) return
        val now = server.overworld().gameTime
        val key = squad.id to kind
        val last = lastSent[key]
        if (last != null && now - last < kind.quietTicks) return
        lastSent[key] = now
        owner.sendSystemMessage(
            Component.literal("[${squad.name}] ").withStyle(squad.faction.accentColor)
                .append(Component.literal(text).withStyle(kind.color))
        )
    }

    /** [spotter] of a squad has a hostile in its sights — called in only if it's a new fight. */
    fun contact(spotter: NpcEntity, hostile: Entity) {
        val squad = spotter.currentSquad() ?: return
        val server = spotter.server ?: return
        val now = server.overworld().gameTime
        val known = fights.getOrPut(squad.owner) { ArrayList() }
        known.removeIf { now - it.lastSeen > CONTACT_QUIET_TICKS }
        val here = spotter.position()
        known.firstOrNull { it.at.closerThan(here, CONTACT_AREA) }?.let {
            it.lastSeen = now
            return
        }
        known += Fight(here, now)
        val to = hostile.position().subtract(here)
        send(server, squad, Kind.CONTACT, "Contact ${bearing(to)}, ${to.horizontalDistance().toInt()} m")
    }

    /** A member of [squad] is down. Called while the man is still on it, so a squad losing its
     *  last man can still be named. */
    fun memberDown(squad: Squad, server: MinecraftServer) {
        val full = maxOf(strength[squad.id] ?: 0, squad.originalComposition.size, squad.members.size)
        strength[squad.id] = full
        val left = squad.members.size - 1
        when {
            left <= 0 -> send(server, squad, Kind.DESTROYED, "Squad destroyed")
            left * 2 <= full && halfCalled.add(squad.id) -> send(server, squad, Kind.CASUALTIES, "Half the squad lost, $left left")
        }
    }

    /** [npc] is off to a Supply. */
    fun goingToResupply(npc: NpcEntity) {
        val squad = npc.currentSquad() ?: return
        val server = npc.server ?: return
        send(server, squad, Kind.RESUPPLY, "${className(npc)} going to resupply")
    }

    /** [squad] has taken its point, or fallen back to it, and holds it now. */
    fun holding(server: MinecraftServer, squad: Squad, was: SquadOrder) {
        send(server, squad, Kind.ARRIVED, if (was == SquadOrder.RETREAT) "Fallen back, holding" else "Objective taken, holding")
    }

    fun tick(server: MinecraftServer) {
        if (server.tickCount % CHECK_TICKS != 0) return
        val mgr = SquadManager.get(server)
        for (squad in mgr.all()) {
            // Topped back up past half: the next time it falls to half is news again.
            val full = strength[squad.id]
            if (full != null && squad.members.size * 2 > full) halfCalled.remove(squad.id)
            val members = squad.members.mapNotNull { SquadManager.findEntity(server, it) as? NpcEntity }.filter { it.isAlive }
            if (members.isEmpty()) continue
            val short = members.count { it.ammoFraction() < LOW_AMMO_FRACTION }
            if (short > 0 && members.none { it.nearestSupply() != null }) {
                send(server, squad, Kind.LOW_AMMO, "$short low on ammo, no Supply in reach")
            }
            moveArrival(server, squad, members)
        }
    }

    /** A MOVE has nothing to switch to on arrival — see [OrderArrival] — so it's called in here. */
    private fun moveArrival(server: MinecraftServer, squad: Squad, members: List<NpcEntity>) {
        if (squad.order != SquadOrder.MOVE || arrivalCalled[squad.id] == squad.orderStamp) return
        val goal = squad.objective ?: return
        val point = Vec3(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5)
        val there = members.count { SquadFormation.reachedPoint(it, point, squad.members.size) }
        if (there * 4 < members.size * 3) return
        arrivalCalled[squad.id] = squad.orderStamp
        send(server, squad, Kind.ARRIVED, "In position")
    }

    /** "MACHINE_GUNNER" -> "Machine gunner". */
    private fun className(npc: NpcEntity): String =
        npc.npcClass.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun bearing(v: Vec3): String {
        val deg = Math.toDegrees(Math.atan2(v.x, -v.z)).let { if (it < 0) it + 360 else it }
        return COMPASS[((deg + 22.5) / 45).toInt() % 8]
    }

    private val COMPASS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

    fun clearAll() {
        lastSent.clear()
        arrivalCalled.clear()
        fights.clear()
        strength.clear()
        halfCalled.clear()
    }
}
