package com.sbwnpc.squad.client

import com.sbwnpc.squad.network.MapSubscribePayload
import net.minecraft.nbt.CompoundTag
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The last map feed from the server, for whichever map mod is drawing it.
 *
 * Kept apart from the map integration itself on purpose: this class is always loaded, the
 * integration only when its mod is, so nothing here may mention a map mod's API.
 */
@EventBusSubscriber(Dist.CLIENT)
object MapState {
    /** Set by a map integration when it starts; asks the server for the feed on joining a world. */
    @Volatile var mapModPresent = false

    /** Called on the client thread with each new feed. */
    @Volatile var onUpdate: ((CompoundTag) -> Unit)? = null

    var latest: CompoundTag? = null
        private set

    fun accept(feed: CompoundTag) {
        latest = feed
        onUpdate?.invoke(feed)
    }

    @SubscribeEvent
    fun onLogin(event: ClientPlayerNetworkEvent.LoggingIn) {
        latest = null
        if (mapModPresent) PacketDistributor.sendToServer(MapSubscribePayload)
    }

    @SubscribeEvent
    fun onLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        latest = null
        onUpdate?.invoke(CompoundTag())
    }
}
