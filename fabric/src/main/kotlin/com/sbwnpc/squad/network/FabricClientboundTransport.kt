package com.sbwnpc.squad.network

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer

/** Fabric delivery of the shared clientbound packet contract. */
object FabricClientboundTransport : ClientboundTransport {
    override fun send(player: ServerPlayer, payload: PacketPayload) {
        ServerPlayNetworking.send(player, payload)
    }
}
