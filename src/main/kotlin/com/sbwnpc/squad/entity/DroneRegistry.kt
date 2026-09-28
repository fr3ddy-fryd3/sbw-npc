package com.sbwnpc.squad.entity

import com.sbwnpc.squad.domain.port.Ports
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent

/**
 * Every loaded piloted drone (SBW's own and the Drone Warfare addon's alike) per level.
 * `AntiDroneBehaviour` asks "any hostile drone near me?" from every NPC every few ticks; walking
 * this beats a 96-block entity box query per NPC.
 */
@EventBusSubscriber
object DroneRegistry : EntityIndex({ Ports.drones.isPiloted(it) }) {
    @SubscribeEvent
    fun onJoin(event: EntityJoinLevelEvent) = join(event)

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) = leave(event)
}
