package com.sbwnpc.squad.util

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.tick.ServerTickEvent

/** NeoForge's server tick events, handed to [PerfProbe]. */
@EventBusSubscriber
object PerfProbeEvents {
    @SubscribeEvent
    fun onServerTickPre(event: ServerTickEvent.Pre) = PerfProbe.onServerTickPre()

    @SubscribeEvent
    fun onServerTickPost(event: ServerTickEvent.Post) = PerfProbe.onServerTickPost(event.server)
}
