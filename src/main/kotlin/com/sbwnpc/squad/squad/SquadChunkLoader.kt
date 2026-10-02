package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Keeps the world loaded around busy squads, so they go on moving and fighting away from players.
 *
 * Past a player's range the chunks unload — ground, blocks and the NPCs with them — and a squad
 * sent a thousand blocks off simply stopped where the player left it. Here each busy squad holds a
 * small region ticket over every chunk its men are in, enough for them to walk, path and fight
 * exactly as they would near a player. Busy means a fight on, or an order that takes it somewhere
 * it isn't yet; a squad holding a position with nothing to shoot at lets go and sleeps.
 *
 * Busy squads keep their tickets near players too. It costs nothing there — the chunks are loaded
 * anyway — and dropping them there was worse than useless: a player's own range only runs
 * entities a couple of chunks short of its edge, and squads in that gap froze as the player flew
 * back toward them. Only squads away from every player count against
 * [SquadConfig.ACTIVE_SQUAD_CHUNK_LIMIT], those in a fight first.
 *
 * Where each squad was is kept on the squad itself ([Squad.lastSeen]), so one ordered off while
 * asleep — even after a restart — is found and woken where it was left. A squad's assigned
 * barracks also holds a ticket at its own position, independently of where the men went, so its
 * reinforcement timer keeps running. SquadManager saves that link even for an empty garrison;
 * destroying the barracks or removing its last assigned squad releases the ticket.
 */
object SquadChunkLoader {
    private val TICKET: TicketType<ChunkPos> = TicketType.create("sbwnpc_squad", Comparator.comparingLong(ChunkPos::toLong))

    /** Region ticket reach: the chunk a man is in and its neighbours run entities; the ring past
     *  them is loaded so paths and sight lines have ground to work on. */
    private const val TICKET_DISTANCE = 3
    private const val INTERVAL_TICKS = 20
    /** Closer than this to its objective, a moving squad has arrived. */
    private const val ARRIVED = 12.0
    /** Moved this far since it was last written down: worth saving. */
    private const val RESAVE_DISTANCE = 8

    private data class Spot(val dimension: ResourceKey<Level>, val chunk: ChunkPos)

    private val held = HashSet<Spot>()
    /** The chunks each squad's men were in, while they were loaded. */
    private val lastSpots = HashMap<UUID, Set<Spot>>()

    fun tick(server: MinecraftServer) {
        if (server.tickCount % INTERVAL_TICKS != 0) return
        val mgr = SquadManager.get(server)
        val limit = SquadConfig.ACTIVE_SQUAD_CHUNK_LIMIT.get()
        val wanted = HashSet<Spot>()
        val farBusy = ArrayList<kotlin.Pair<Squad, Int>>()
        for (squad in mgr.all()) {
            val members = squad.members.mapNotNull { SquadManager.findEntity(server, it) as? NpcEntity }.filter { it.isAlive }
            if (members.isNotEmpty()) remember(mgr, squad, members)
            if (limit <= 0) continue
            squad.barracks?.let { wanted += Spot(it.dimension, ChunkPos(it.pos)) }
            val fighting = members.any { it.target?.isAlive == true }
            if (!fighting && !underway(squad)) continue
            if (nearPlayer(server, squad)) wanted += spotsOf(squad)
            else farBusy += squad to if (fighting) 0 else 1
        }
        farBusy.sortBy { it.second }
        for ((squad, _) in farBusy.take(limit)) wanted += spotsOf(squad)

        for (spot in held - wanted) server.getLevel(spot.dimension)?.chunkSource?.removeRegionTicket(TICKET, spot.chunk, TICKET_DISTANCE, spot.chunk)
        for (spot in wanted - held) server.getLevel(spot.dimension)?.chunkSource?.addRegionTicket(TICKET, spot.chunk, TICKET_DISTANCE, spot.chunk)
        if (wanted != held) DebugFlags.log(LogGroup.CHUNK, "squads and barracks hold {} chunks (was {})", wanted.size, held.size)
        held.clear()
        held += wanted
        lastSpots.keys.retainAll(mgr.all().map { it.id }.toSet())
    }

    private fun remember(mgr: SquadManager, squad: Squad, members: List<NpcEntity>) {
        lastSpots[squad.id] = members.map { Spot(it.level().dimension(), ChunkPos(it.blockPosition())) }.toSet()
        val centre = BlockPos.containing(
            members.sumOf { it.x } / members.size, members.sumOf { it.y } / members.size, members.sumOf { it.z } / members.size
        )
        val dim = members[0].level().dimension()
        val old = squad.lastSeen
        if (old == null || squad.lastSeenDim != dim || old.distManhattan(centre) > RESAVE_DISTANCE) {
            squad.lastSeen = centre
            squad.lastSeenDim = dim
            mgr.setDirty()
        }
    }

    private fun spotsOf(squad: Squad): Set<Spot> {
        lastSpots[squad.id]?.let { return it }
        val at = squad.lastSeen ?: return emptySet()
        return setOf(Spot(squad.lastSeenDim ?: Level.OVERWORLD, ChunkPos(at)))
    }

    /** An order that takes the squad somewhere it has not reached yet. */
    private fun underway(squad: Squad): Boolean {
        if (squad.order != SquadOrder.MOVE && squad.order != SquadOrder.ATTACK && squad.order != SquadOrder.RETREAT) return false
        val goal = squad.objective ?: return squad.focusEntity != null
        val at = squad.lastSeen ?: return true
        return Vec3.atCenterOf(at).distanceTo(Vec3.atCenterOf(goal)) > ARRIVED
    }

    /** Within some player's simulation range — a ticket there is free. */
    private fun nearPlayer(server: MinecraftServer, squad: Squad): Boolean {
        val at = squad.lastSeen ?: return false
        val reach = server.playerList.simulationDistance * 16.0 + 32.0
        return server.playerList.players.any {
            it.level().dimension() == (squad.lastSeenDim ?: Level.OVERWORLD) &&
                it.position().distanceToSqr(at.x + 0.5, it.y, at.z + 0.5) <= reach * reach
        }
    }

    fun clearAll() {
        held.clear()
        lastSpots.clear()
    }
}
