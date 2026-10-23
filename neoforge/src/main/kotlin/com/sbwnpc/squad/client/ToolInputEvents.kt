package com.sbwnpc.squad.client

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.InputEvent

/** Hands NeoForge's use-item click to [ToolInput]; cancelling stops the normal right-click (open GUI / deploy). */
@EventBusSubscriber(Dist.CLIENT)
object ToolInputEvents {
    @SubscribeEvent
    fun onInteractionKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem || !ToolInput.toggleModeOnControlClick(event.hand)) return
        event.isCanceled = true
        event.setSwingHand(false)
    }
}
