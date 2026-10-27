package com.sbwnpc.squad.network

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** Fabric delivery of packets to the server. */
object FabricServerboundTransport : ServerboundTransport {
    override fun send(payload: PacketPayload) {
        ClientPlayNetworking.send(payload)
    }
}
