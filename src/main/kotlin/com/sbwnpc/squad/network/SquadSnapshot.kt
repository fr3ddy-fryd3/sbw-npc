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

/** Server-side: where the player's faction stands with every other one, for the Diplomacy screen. */
fun buildDiplomacySnapshot(player: ServerPlayer): CompoundTag {
    val tag = CompoundTag()
    val own = com.sbwnpc.squad.squad.PlayerFactionRegistry.get(player.server).get(player.uuid) ?: return tag
    val diplomacy = com.sbwnpc.squad.team.Diplomacy
    tag.putString("Own", own.name)
    tag.putBoolean("InAlliance", diplomacy.alliesOf(own).size > 1)
    tag.putBoolean("WantsOut", diplomacy.wantsOut(own, player.uuid))
    val rows = net.minecraft.nbt.ListTag()
    for (other in com.sbwnpc.squad.npc.SquadFaction.entries) {
        if (other == own) continue
        val row = CompoundTag()
        row.putString("Faction", other.name)
        when {
            diplomacy.allied(own, other) -> row.putString("Status", "ALLY")
            diplomacy.truceTicksLeft(own, other) > 0 -> {
                row.putString("Status", "TRUCE")
                row.putInt("TruceSeconds", (diplomacy.truceTicksLeft(own, other) / 20).toInt())
            }
            else -> row.putString("Status", "HOSTILE")
        }
        val offer = diplomacy.proposals().firstOrNull { p ->
            p.members and com.sbwnpc.squad.team.AllianceRegistry.bit(own) != 0 &&
                p.members and com.sbwnpc.squad.team.AllianceRegistry.bit(other) != 0
        }
        if (offer != null && !diplomacy.allied(own, other)) {
            row.putInt("Offer", offer.id)
            row.putBoolean("Voted", offer.yes[own.ordinal]?.contains(player.uuid) == true)
            row.putString("Members", com.sbwnpc.squad.team.AllianceRegistry.membersOf(offer.members).joinToString(", ") { it.label })
        }
        rows.add(row)
    }
    tag.put("Rows", rows)
    return tag
}
