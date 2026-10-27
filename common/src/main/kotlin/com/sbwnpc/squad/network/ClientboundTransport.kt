package com.sbwnpc.squad.network

import net.minecraft.server.level.ServerPlayer

/** Sends gameplay packets to a player through the active loader. */
fun interface ClientboundTransport {
    fun send(player: ServerPlayer, payload: PacketPayload)
}

/** Installed by the loader before server gameplay begins. */
object ClientboundPackets {
    lateinit var transport: ClientboundTransport
}

fun sendToClient(player: ServerPlayer, payload: PacketPayload) {
    ClientboundPackets.transport.send(player, payload)
}

/** Sends gameplay packets to the server through the active loader. */
fun interface ServerboundTransport {
    fun send(payload: PacketPayload)
}

/** Installed by the loader's client before the player can open a screen. */
object ServerboundPackets {
    lateinit var transport: ServerboundTransport
}

fun sendToServer(payload: PacketPayload) {
    ServerboundPackets.transport.send(payload)
}
