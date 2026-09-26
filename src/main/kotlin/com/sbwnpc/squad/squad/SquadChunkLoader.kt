package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Keeps the world loaded around squads that are busy far from every player.
 *
 * Out past a player's range the chunks unload — ground, blocks and the NPCs with them — and a
 * squad sent a thousand blocks off simply stopped where the player left it. Here each busy squad
 * holds a small region ticket over every chunk its men are in, enough for them to walk, path and
 * fight exactly as they would near a player. Busy means a fight on, or an order that takes it
 * somewhere it isn't yet; a squad holding a position with nothing to shoot at lets go and sleeps.
 * At most [SquadConfig.ACTIVE_SQUAD_CHUNK_LIMIT] squads at once, those in a fight first.
 *
 * Positions are remembered from the last time a squad's men were loaded, so one ordered off while
 * asleep is found and woken where it was left.
 */
object SquadChunkLoader {
    private val TICKET: TicketType<ChunkPos> = TicketType.create("sbwnpc_squad", Comparator.comparingLong(ChunkPos::toLong))

    /** Region ticket reach: the chunk a man is in and its neighbours run entities; the ring past
     *  them is loaded so paths and sight lines have ground to work on. */
    private const val TICKET_DISTANCE = 3
    private const val INTERVAL_TICKS = 20
    /** Closer than this to its objective, a moving squad has arrived. */
    private const val ARRIVED = 12.0

    private data class Spot(val dimension: ResourceKey<Level>, val chunk: ChunkPos)
    private class Last(val spots: Set<Spot>, val centre: Vec3, val dimension: ResourceKey<Level>)

    private val held = HashSet<Spot>()
    private val lastKnown = HashMap<UUID, Last>()

    fun tick(server: MinecraftServer) {
        if (server.tickCount % INTERVAL_TICKS != 0) return
        val limit = SquadConfig.ACTIVE_SQUAD_CHUNK_LIMIT.get()
        val wanted = HashSet<Spot>()
        if (limit > 0) {
            val busy = ArrayList<kotlin.Pair<Squad, Int>>()
            for (squad in SquadManager.get(server).all()) {
                val members = squad.members.mapNotNull { SquadManager.findEntity(server, it) as? NpcEntity }
                    .filter { it.isAlive }
                if (members.isNotEmpty()) {
                    val dim = members[0].level().dimension()
                    lastKnown[squad.id] = Last(
                        members.map { Spot(it.level().dimension(), ChunkPos(it.blockPosition())) }.toSet(),
                        Vec3(members.sumOf { it.x } / members.size, members.sumOf { it.y } / members.size, members.sumOf { it.z } / members.size),
                        dim
                    )
                }
                val last = lastKnown[squad.id] ?: continue
                if (nearPlayer(server, last)) continue
                val fighting = members.any { it.target?.isAlive == true }
                if (fighting) busy += squad to 0 else if (underway(squad, last)) busy += squad to 1
            }
            busy.sortBy { it.second }
            for ((squad, _) in busy.take(limit)) wanted += lastKnown.getValue(squad.id).spots
        }
        for (spot in held - wanted) server.getLevel(spot.dimension)?.chunkSource?.removeRegionTicket(TICKET, spot.chunk, TICKET_DISTANCE, spot.chunk)
        for (spot in wanted - held) server.getLevel(spot.dimension)?.chunkSource?.addRegionTicket(TICKET, spot.chunk, TICKET_DISTANCE, spot.chunk)
        if (wanted != held) DebugFlags.log("[chunk-debug] squads hold {} chunks (was {})", wanted.size, held.size)
        held.clear()
        held += wanted
        lastKnown.keys.retainAll(SquadManager.get(server).all().map { it.id }.toSet())
    }

    /** An order that takes the squad somewhere it has not reached yet. */
    private fun underway(squad: Squad, last: Last): Boolean {
        if (squad.order != SquadOrder.MOVE && squad.order != SquadOrder.ATTACK && squad.order != SquadOrder.RETREAT) return false
        val goal = squad.objective ?: return squad.focusEntity != null
        return last.centre.distanceTo(Vec3(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5)) > ARRIVED
    }

    /** Inside some player's simulation range already — no ticket needed. */
    private fun nearPlayer(server: MinecraftServer, last: Last): Boolean {
        val reach = (server.playerList.simulationDistance - 1).coerceAtLeast(1) * 16.0
        return server.playerList.players.any {
            it.level().dimension() == last.dimension && it.position().distanceToSqr(last.centre.x, it.y, last.centre.z) <= reach * reach
        }
    }

    fun clearAll() {
        held.clear()
        lastKnown.clear()
    }
}
