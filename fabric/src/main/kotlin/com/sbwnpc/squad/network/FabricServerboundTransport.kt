package com.sbwnpc.squad.network

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Fabric delivery of packets to the server. */
object FabricServerboundTransport : ServerboundTransport {
    override fun send(payload: CustomPacketPayload) {
        ClientPlayNetworking.send(payload)
    }
}
