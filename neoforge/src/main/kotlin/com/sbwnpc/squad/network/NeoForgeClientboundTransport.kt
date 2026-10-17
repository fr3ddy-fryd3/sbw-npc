package com.sbwnpc.squad.network

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

/** NeoForge delivery of the shared clientbound packet contract. */
object NeoForgeClientboundTransport : ClientboundTransport {
    override fun send(player: ServerPlayer, payload: CustomPacketPayload) {
        PacketDistributor.sendToPlayer(player, payload)
    }
}
