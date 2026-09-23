package com.sbwnpc.squad.network

import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

fun sendToClient(player: ServerPlayer, payload: CustomPacketPayload) {
    PacketDistributor.sendToPlayer(player, payload)
}

/** Server-side: pack the player's squads into a tag the CommandScreen reads. */
fun buildSquadSnapshot(mgr: SquadManager, owner: UUID, looseCount: Int): CompoundTag = CompoundTag().apply {
    putInt("Loose", looseCount)
    put("Squads", ListTag().apply {
        mgr.forOwner(owner).forEach { s ->
            add(CompoundTag().apply {
                putString("Id", s.id.toString())
                putString("Name", s.name)
                putString("Faction", s.faction.name)
                putInt("Members", s.members.size)
                putInt("Order", s.order.ordinal)
                putBoolean("Tank", mgr.isTankSquad(s))
                putBoolean("Mortar", mgr.isMortarSquad(s))
                putBoolean("Gunship", mgr.isGunshipSquad(s))
                putBoolean("Transport", mgr.isTransportSquad(s))
                s.barracks?.let { putLong("Barracks", it.pos.asLong()) }
            })
        }
    })
}
