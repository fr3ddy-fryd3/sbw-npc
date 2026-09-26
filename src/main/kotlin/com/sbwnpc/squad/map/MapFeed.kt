package com.sbwnpc.squad.map

import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.MortarOperatorBehaviour
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
import net.minecraft.server.level.ServerLevel
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
        com.sbwnpc.squad.combat.DebugFlags.log("[map-debug] {} subscribed to the map feed", player.gameProfile.name)
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

        val squads = SquadManager.get(server)
        val routes = RouteManager.get(server)
        val squadList = ListTag()
        val inSquad = HashSet<UUID>()
        // The player's own squads whatever faction they were raised in (anyone may field several),
        // plus everything on the sides allied with the player's faction.
        val showAll = com.sbwnpc.squad.combat.DebugFlags.MAP_SHOWS_ALL
        val shown = if (showAll) SquadFaction.entries.toMutableSet() else sides.toMutableSet()
        for (squad in squads.all()) if (squad.owner == player.uuid) shown += squad.faction
        for (squad in squads.all()) {
            if (squad.owner != player.uuid && squad.faction !in shown) continue
            val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
            inSquad += squad.members
            if (members.isEmpty()) continue
            val t = CompoundTag()
            t.putUUID("Id", squad.id)
            t.putString("Name", squad.name)
            t.putInt("F", squad.faction.ordinal)
            t.putBoolean("Own", squad.owner == player.uuid)
            t.putString("Order", squad.order.name)
            t.putBoolean("Mortar", squads.isMortarSquad(squad))
            t.putInt("N", members.size)
            t.putInt("X", members.sumOf { it.x }.div(members.size).toInt())
            t.putInt("Z", members.sumOf { it.z }.div(members.size).toInt())
            squad.objective?.let { t.putIntArray("Obj", intArrayOf(it.x, it.y, it.z)) }
            if (squad.order == SquadOrder.BARRAGE) t.putInt("Zone", MortarOperatorBehaviour.BARRAGE_RADIUS.toInt())
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
