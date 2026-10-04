package com.sbwnpc.squad

import com.sbwnpc.squad.combat.DeathSites
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.DroneRegistry
import com.sbwnpc.squad.entity.GrenadeRegistry
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.DroneLinks
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.squad.RouteRecording
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.vehicle.PilotlessHelicopters
import com.sbwnpc.squad.squad.SquadSelection
import com.sbwnpc.squad.team.SquadTeams
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent

/** Clears runtime-only state so UUIDs cannot leak into the next world or login session. */
@EventBusSubscriber
object ServerLifecycle {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onEntityJoin(event: net.neoforged.neoforge.event.entity.EntityJoinLevelEvent) {
        val level = event.level as? net.minecraft.server.level.ServerLevel ?: return
        if (SquadManager.get(level).discardDeletedEntity(event.entity)) event.isCanceled = true
    }

    @SubscribeEvent
    fun onServerStarted(event: net.neoforged.neoforge.event.server.ServerStartedEvent) {
        com.sbwnpc.squad.team.Diplomacy.attach(event.server)
    }

    @SubscribeEvent
    fun onServerStopped(event: ServerStoppedEvent) {
        com.sbwnpc.squad.team.Diplomacy.detach()
        com.sbwnpc.squad.map.MapFeed.clearAll()
        com.sbwnpc.squad.combat.Withdrawal.clearAll()
        com.sbwnpc.squad.entity.ai.GrenadeUseBehaviour.clearAll()
        com.sbwnpc.squad.entity.ai.SquadMarch.clearAll()
        com.sbwnpc.squad.entity.ai.BoatTrips.clearAll()
        com.sbwnpc.squad.vehicle.WaterMap.clearAll()
        com.sbwnpc.squad.route.GroundMap.clearAll()
        com.sbwnpc.squad.route.PlanBudget.clearAll()
        com.sbwnpc.squad.squad.SquadChunkLoader.clearAll()
        TeamAwareness.clearAll()
        com.sbwnpc.squad.squad.SquadReports.clearAll()
        com.sbwnpc.squad.combat.Hearing.clearAll()
        DeathSites.clearAll()
        MortarClaims.clearAll()
        DroneLinks.clearAll()
        VehicleTransportClaims.clearAll()
        SquadSelection.clearAll()
        RouteRecording.clearAll()
        SquadManager.clearCache()
        NpcRegistry.clearAll()
        PilotlessHelicopters.clear()
        FiringSpots.clearAll()
        DroneRegistry.clearAll()
        GrenadeRegistry.clearAll()
        com.sbwnpc.squad.combat.StrayRounds.clearAll()
        SquadTeams.clearCache()
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        com.sbwnpc.squad.squad.PlayerFactionRegistry.get(player.server).sync(player)
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        SquadSelection.clear(event.entity.uuid)
        RouteRecording.cancel(event.entity.uuid)
        com.sbwnpc.squad.map.MapFeed.unsubscribe(event.entity.uuid)
    }
}
