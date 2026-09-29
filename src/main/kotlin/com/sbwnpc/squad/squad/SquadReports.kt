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
 * Each kind of call has its own quiet interval per squad, so a firefight reads as "contact" once
 * and a volley of casualties as one line with the count, not a wall of them. Runtime only: calls
 * missed while offline are gone. A player can switch them all off ([ReportMutes]).
 */
object SquadReports {

    /** [ongoing]: every sighting restarts the quiet interval, so a firefight is called in once
     *  at its start and again only after it has gone quiet for that long. */
    private enum class Kind(val quietTicks: Long, val color: ChatFormatting, val ongoing: Boolean = false) {
        CONTACT(600, ChatFormatting.RED, ongoing = true),
        CASUALTIES(0, ChatFormatting.GOLD),
        DESTROYED(0, ChatFormatting.DARK_RED),
        LOW_AMMO(1200, ChatFormatting.YELLOW),
        RESUPPLY(600, ChatFormatting.GRAY),
        ARRIVED(0, ChatFormatting.GREEN),
    }

    private val lastSent = HashMap<Pair<UUID, Kind>, Long>()
    /** Deaths not yet called in, per squad — flushed as one line every [FLUSH_TICKS]. */
    private val pendingDeaths = HashMap<UUID, Int>()
    /** The order stamp a MOVE arrival was last called in for, per squad. */
    private val arrivalCalled = HashMap<UUID, Int>()

    private const val FLUSH_TICKS = 100
    private const val LOW_AMMO_FRACTION = 0.3

    private fun send(server: MinecraftServer, squad: Squad, kind: Kind, text: String) {
        val owner = server.playerList.getPlayer(squad.owner) ?: return
        if (ReportMutes.get(server).isMuted(owner.uuid)) return
        val now = server.overworld().gameTime
        val key = squad.id to kind
        val last = lastSent[key]
        if (last != null && now - last < kind.quietTicks) {
            if (kind.ongoing) lastSent[key] = now
            return
        }
        lastSent[key] = now
        owner.sendSystemMessage(
            Component.literal("[${squad.name}] ").withStyle(squad.faction.accentColor)
                .append(Component.literal(text).withStyle(kind.color))
        )
    }

    /** [spotter] of a squad has a hostile in its sights. */
    fun contact(spotter: NpcEntity, hostile: Entity) {
        val squad = spotter.currentSquad() ?: return
        val server = spotter.server ?: return
        val to = hostile.position().subtract(spotter.position())
        send(server, squad, Kind.CONTACT, "Contact ${bearing(to)}, ${to.horizontalDistance().toInt()} m")
    }

    /** A member of [squad] is down; called in with the others lost around the same time. Called
     *  while the man is still on the squad, so its owner is known even if he was the last. */
    fun memberDown(squad: Squad) {
        pendingDeaths.merge(squad.id, 1, Int::plus)
        ownerOfLost[squad.id] = squad.owner to squad.name
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
        if (server.tickCount % FLUSH_TICKS != 0) return
        val mgr = SquadManager.get(server)
        flushDeaths(server, mgr)
        for (squad in mgr.all()) {
            val members = squad.members.mapNotNull { SquadManager.findEntity(server, it) as? NpcEntity }.filter { it.isAlive }
            if (members.isEmpty()) continue
            val short = members.count { it.ammoFraction() < LOW_AMMO_FRACTION }
            if (short > 0 && members.none { it.nearestSupply() != null }) {
                send(server, squad, Kind.LOW_AMMO, "$short low on ammo, no Supply in reach")
            }
            moveArrival(server, squad, members)
        }
    }

    private fun flushDeaths(server: MinecraftServer, mgr: SquadManager) {
        if (pendingDeaths.isEmpty()) return
        val deaths = HashMap(pendingDeaths)
        pendingDeaths.clear()
        for ((id, count) in deaths) {
            val squad = mgr.get(id)
            val left = squad?.members?.size ?: 0
            if (squad == null || left == 0) {
                // Pruned with its last man, unless a Barracks keeps it — either way it's gone.
                val (owner, name) = ownerOfLost[id] ?: continue
                if (ReportMutes.get(server).isMuted(owner)) continue
                server.playerList.getPlayer(owner)?.sendSystemMessage(
                    Component.literal("[$name] ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("Squad destroyed").withStyle(Kind.DESTROYED.color))
                )
                continue
            }
            send(server, squad, Kind.CASUALTIES, if (count == 1) "Man down, $left left" else "$count men down, $left left")
        }
        ownerOfLost.clear()
    }

    /** Owner and name of each squad with deaths queued. */
    private val ownerOfLost = HashMap<UUID, Pair<UUID, String>>()

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
        pendingDeaths.clear()
        arrivalCalled.clear()
        ownerOfLost.clear()
    }
}
