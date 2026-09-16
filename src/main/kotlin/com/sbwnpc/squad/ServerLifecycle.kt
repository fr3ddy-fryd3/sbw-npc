package com.sbwnpc.squad

import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.squad.RouteRecording
import com.sbwnpc.squad.squad.SquadSelection
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent

/** Clears runtime-only state so UUIDs cannot leak into the next world or login session. */
@EventBusSubscriber
object ServerLifecycle {
    @SubscribeEvent
    fun onServerStopped(event: ServerStoppedEvent) {
        TeamAwareness.clearAll()
        MortarClaims.clearAll()
        VehicleTransportClaims.clearAll()
        SquadSelection.clearAll()
        RouteRecording.clearAll()
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        SquadSelection.clear(event.entity.uuid)
        RouteRecording.cancel(event.entity.uuid)
    }
}
