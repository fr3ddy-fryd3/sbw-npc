package com.sbwnpc.squad.entity

import com.sbwnpc.squad.combat.StrayRounds
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent

/**
 * Feeds NeoForge's join and leave events to the [EntityIndex]es. Normal priority without cancelled
 * events, so an entity whose join another handler cancelled is never indexed.
 */
@EventBusSubscriber
object EntityIndexEvents {
    @SubscribeEvent
    fun onJoin(event: EntityJoinLevelEvent) {
        GrenadeRegistry.join(event.entity, event.level)
        DroneRegistry.join(event.entity, event.level)
        StrayRounds.join(event.entity, event.level)
    }

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) {
        GrenadeRegistry.leave(event.entity, event.level)
        DroneRegistry.leave(event.entity, event.level)
        StrayRounds.leave(event.entity, event.level)
    }
}
