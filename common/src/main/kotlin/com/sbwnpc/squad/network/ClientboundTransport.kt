package com.sbwnpc.squad.network

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/** Sends gameplay packets to a player through the active loader. */
fun interface ClientboundTransport {
    fun send(player: ServerPlayer, payload: CustomPacketPayload)
}

/** Installed by the loader before server gameplay begins. */
object ClientboundPackets {
    lateinit var transport: ClientboundTransport
}

fun sendToClient(player: ServerPlayer, payload: CustomPacketPayload) {
    ClientboundPackets.transport.send(player, payload)
}
