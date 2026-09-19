package com.sbwnpc.squad

import com.sbwnpc.squad.squad.SelectionHighlight
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.tick.ServerTickEvent

@EventBusSubscriber
object ServerTick {
    @SubscribeEvent
    fun onServerTick(event: ServerTickEvent.Post) {
        SelectionHighlight.tick(event.server)
    }
}
