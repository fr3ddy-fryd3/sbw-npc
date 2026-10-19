package com.sbwnpc.squad.network

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.experience.ExperienceRules
import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Reads loaded entities only. Opening a page never loads a chunk or invents an offline profile. */
fun buildSquadDetailsSnapshot(manager: SquadManager, server: MinecraftServer, owner: UUID, id: UUID, refresh: Boolean = false): CompoundTag? {
    val squad = manager.get(id)?.takeIf { it.owner == owner } ?: return null
    // Legacy roles resolve only from loaded entities; absent members continue reserving their places.
    manager.forOwner(owner).forEach { manager.ensureRecruitmentRoster(server.overworld(), it) }
    return CompoundTag().apply {
        putString("Id", id.toString())
        putString("Name", squad.name)
        putString("Faction", squad.faction.label)
        putBoolean("Refresh", refresh)
        putInt("Order", squad.order.ordinal)
        putBoolean("Tank", manager.isTankSquad(squad))
        putBoolean("Mortar", manager.isMortarSquad(squad))
        putBoolean("Gunship", manager.isGunshipSquad(squad))
        putBoolean("Transport", manager.isTransportSquad(squad))
        squad.barracks?.let { putLong("Barracks", it.pos.asLong()) }
        squad.objective?.let { putString("Objective", "${it.x} ${it.y} ${it.z}") }
        put(
            "Members",
            ListTag().apply {
                squad.members.forEach { member ->
                    add(
                        CompoundTag().apply {
                            putUUID("Id", member)
                            val npc = SquadManager.findEntity(server, member) as? NpcEntity
                            putBoolean("Loaded", npc != null)
                            if (npc != null) {
                                npc.ensureExperienceIdentity()
                                putString("Name", npc.name.string)
                                putString("Role", npc.npcClass.name)
                                putString("Rank", npc.npcRank.name)
                                putDouble("Experience", npc.experience)
                                ExperienceRules.next(npc.npcRank)?.let { putDouble("Next", it) }
                                putFloat("Health", npc.health)
                                putFloat("MaxHealth", npc.maxHealth)
                                putInt("PrimaryAmmo", Ports.guns.roundsLeft(npc.primaryGun()))
                                putInt("PistolAmmo", Ports.guns.roundsLeft(npc.sidearmWeapon))
                            }
                        },
                    )
                }
            },
        )
        put(
            "MergeCandidates",
            ListTag().apply {
                manager.forOwner(owner).filter { manager.mergeProblem(id, it.id, owner) == null }.forEach {
                    add(
                        CompoundTag().apply {
                            putString("Id", it.id.toString())
                            putString("Name", it.name)
                            putInt("Members", it.members.size)
                            putString("Order", it.order.name)
                        },
                    )
                }
            },
        )
        putString(
            "MergeNote",
            when {
                squad.barracks != null -> "Detach from barracks before merging"
                getList("MergeCandidates", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt()).isEmpty() -> "No compatible infantry squads without barracks"
                else -> "The destination keeps its name, orders and objective"
            },
        )
    }
}
