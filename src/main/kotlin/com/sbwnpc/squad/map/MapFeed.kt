package com.sbwnpc.squad.map

import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.MortarCrewBehaviour
import com.sbwnpc.squad.network.MapFeedPayload
import com.sbwnpc.squad.network.sendToClient
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.squad.RouteManager
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.Diplomacy
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import java.util.UUID

/**
 * What a player's map shows, sent once a second to players whose client has a map mod to show it
 * on (they say so when they join — see [subscribe]).
 *
 * The client only knows the entities near it, so everything comes from here: the player's own and
 * allied squads (their middle, not every man), NPCs outside a squad, vehicles, barracks, and the
 * enemies the faction or its allies have in sight right now. Nothing an ally hasn't seen is sent,
 * so the map is not a wallhack.
 */
object MapFeed {
    private const val INTERVAL_TICKS = 20

    private val subscribers = HashSet<UUID>()

    fun subscribe(player: ServerPlayer) {
        subscribers += player.uuid
        com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.MAP, "{} subscribed to the map feed", player.gameProfile.name)
    }

    fun unsubscribe(player: UUID) {
        subscribers -= player
    }

    fun clearAll() = subscribers.clear()

    fun tick(server: MinecraftServer) {
        if (subscribers.isEmpty() || server.tickCount % INTERVAL_TICKS != 0) return
        val factions = PlayerFactionRegistry.get(server)
        for (player in server.playerList.players) {
            if (player.uuid !in subscribers) continue
            val faction = factions.get(player.uuid) ?: continue
            sendToClient(player, MapFeedPayload(build(server, player, faction)))
        }
    }

    private fun build(server: MinecraftServer, player: ServerPlayer, faction: SquadFaction): CompoundTag {
        val level = player.serverLevel()
        val sides = Diplomacy.alliesOf(faction)
        val tag = CompoundTag()
        tag.putString("Dim", level.dimension().location().toString())
        tag.putInt("DefaultFaction", faction.ordinal)

        val squads = SquadManager.get(server)
        val routes = RouteManager.get(server)
        val squadList = ListTag()
        val inSquad = HashSet<UUID>()
        // The player's own squads whatever faction they were raised in (anyone may field several),
        // plus everything on the sides allied with the player's faction.
        val showAll = com.sbwnpc.squad.combat.DebugFlags.mapShowsAll
        val shown = if (showAll) SquadFaction.entries.toMutableSet() else sides.toMutableSet()
        for (squad in squads.all()) if (squad.owner == player.uuid) shown += squad.faction
        for (squad in squads.all()) {
            if (squad.owner != player.uuid && squad.faction !in shown) continue
            // Wiped out and waiting on its barracks for men: nothing out there to show. It used to
            // pass for a squad merely out of range, square and objective waypoint and all.
            if (squad.members.isEmpty()) continue
            val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
            inSquad += squad.members
            // Out of loaded range: shown where it was last seen, so it can still be picked out
            // and ordered (which wakes it — see SquadChunkLoader).
            val asleep = members.isEmpty()
            val lastSeen = squad.lastSeen
            if (asleep && (lastSeen == null || (squad.lastSeenDim ?: net.minecraft.world.level.Level.OVERWORLD) != level.dimension())) continue
            val t = CompoundTag()
            t.putBoolean("Asleep", asleep)
            t.putUUID("Id", squad.id)
            t.putString("Name", squad.name)
            t.putInt("F", squad.faction.ordinal)
            t.putBoolean("Own", squad.owner == player.uuid)
            t.putString("Order", squad.order.name)
            t.putBoolean("Mortar", squads.isMortarSquad(squad))
            t.putBoolean("Tank", squads.isTankSquad(squad))
            t.putBoolean("Gunship", squads.isGunshipSquad(squad))
            t.putBoolean("Transport", squads.isTransportSquad(squad))
            t.putInt("N", if (asleep) squad.members.size else members.size)
            if (asleep) {
                t.putInt("X", lastSeen!!.x)
                t.putInt("Z", lastSeen.z)
            } else {
                t.putInt("X", members.sumOf { it.x }.div(members.size).toInt())
                t.putInt("Z", members.sumOf { it.z }.div(members.size).toInt())
            }
            // Where each of the player's own men stands, for the map to mark — the square alone is
            // the middle, and hides the one stuck behind a hill.
            if (squad.owner == player.uuid && members.isNotEmpty()) {
                t.putIntArray("Men", members.flatMap { listOf(it.blockX, it.blockZ) }.toIntArray())
            }
            squad.objective?.let { t.putIntArray("Obj", intArrayOf(it.x, it.y, it.z)) }
            // The point the squad's operation was ordered to, not this squad's own spot in the
            // line: an order sent to exactly this point joins the operation instead of starting another.
            val operation = com.sbwnpc.squad.ai.integration.NativeTactics.operationOf(level, squad.id)
            val goal = (operation?.objective as? com.sbwnpc.squad.ai.coordination.ObjectiveKey.Area)
                ?.takeIf { it.dimension == level.dimension().location().toString() }
                ?.let { net.minecraft.core.BlockPos(it.x, it.y, it.z) } ?: squad.tacticalObjective ?: squad.objective
            goal?.let {
                t.putIntArray("Goal", intArrayOf(it.x, it.y, it.z))
                t.putString("GoalOrder", operation?.order?.name ?: squad.order.name)
            }
            // Who may join it: the map has no alliance registry of its own.
            t.putInt("Allies", com.sbwnpc.squad.team.Diplomacy.alliesOf(squad.faction).fold(0) { mask, it -> mask or (1 shl it.ordinal) })
            if (squad.order == SquadOrder.BARRAGE) t.putInt("Zone", MortarCrewBehaviour.BARRAGE_RADIUS.toInt())
            if (squad.order == SquadOrder.PATROL) {
                routes.get(squad.routeId)?.points?.let { pts ->
                    t.putIntArray("Route", pts.flatMap { listOf(it.x, it.z) }.toIntArray())
                }
            }
            squad.barracks?.takeIf { it.dimension == level.dimension() }?.let {
                t.putIntArray("Barracks", intArrayOf(it.pos.x, it.pos.z))
            }
            squadList.add(t)
        }
        tag.put("Squads", squadList)

        val loose = ListTag()
        for (npc in NpcRegistry.all(level)) {
            if (!npc.isAlive || npc.uuid in inSquad || npc.vehicle != null) continue
            val side = SquadTeams.factionOf(npc) ?: continue
            if (side !in shown) continue
            loose.add(point(side, npc))
        }
        tag.put("Loose", loose)

        // Friendly vehicles by who is aboard; hostile ones only once seen, through their crew.
        val known = shown.flatMap { TeamAwareness.knownContacts(it, level.gameTime) }.toSet()
        val vehicles = ListTag()
        val enemies = ListTag()
        val seenVehicles = HashSet<UUID>()
        for (entity in level.allEntities) {
            if (!Ports.vehicles.isVehicle(entity) || !entity.isAlive) continue
            val crew = entity.passengers.firstNotNullOfOrNull { SquadTeams.sideOf(it) } ?: continue
            if (crew in shown) {
                vehicles.add(point(crew, entity).also { it.putBoolean("Air", Helicopters.isHelicopter(entity)) })
                seenVehicles += entity.uuid
            }
        }
        for (id in known) {
            val contact = level.getEntity(id) ?: continue
            if (!contact.isAlive) continue
            val side = SquadTeams.sideOf(contact) ?: continue
            if (side in shown) continue
            val ride = contact.vehicle?.takeIf(Ports.vehicles::isVehicle)
            if (ride != null) {
                if (!seenVehicles.add(ride.uuid)) continue
                enemies.add(point(side, ride).also { it.putInt("Kind", if (Helicopters.isHelicopter(ride)) 2 else 1) })
            } else {
                enemies.add(point(side, contact).also { it.putInt("Kind", 0) })
            }
        }
        tag.put("Vehicles", vehicles)
        tag.put("Enemies", enemies)
        return tag
    }

    private fun point(side: SquadFaction, entity: Entity): CompoundTag {
        val t = CompoundTag()
        t.putInt("F", side.ordinal)
        t.putInt("X", entity.blockX)
        t.putInt("Z", entity.blockZ)
        return t
    }
}
