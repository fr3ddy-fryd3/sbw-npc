package com.sbwnpc.squad

import com.sbwnpc.squad.block.SupplySpawns
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.vehicle.DismountHelper
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent

/** Where [SupplySpawns] meets respawning. */
object ForgeSupplySpawns {
    /** A bed or an anchor replaces a Supply spawn, the way one bed replaces another. */
    fun onSetSpawn(event: PlayerSetSpawnEvent) {
        if (SupplySpawns.clearingVanilla || event.newSpawn == null) return
        val player = event.entity as? ServerPlayer ?: return
        SupplySpawns.get(player.server).clear(player.uuid)
    }

    /** Moves a respawned player to their Supply spawn, if they have a working one. */
    fun respawnAt(player: ServerPlayer) {
        val spawns = SupplySpawns.get(player.server)
        val at = spawns.of(player.uuid) ?: return
        val level = player.server.getLevel(at.dimension())
        val supply = level?.getBlockEntity(at.pos()) as? SupplyBlockEntity
        if (level == null || supply == null) {
            spawns.clear(player.uuid)
            tell(player, "Your Supply is gone")
            return
        }
        if (!supply.serves(PlayerFactionRegistry.get(level).get(player.uuid))) {
            tell(player, "Your Supply no longer serves your side")
            return
        }
        val position = safePosition(level, at.pos())
        if (position == null) {
            tell(player, "Your Supply is blocked")
            return
        }
        player.teleportTo(level, position.x, position.y, position.z, player.yRot, 0f)
    }

    /** Vanilla's bed/anchor collision and hazard checks, with nearby alternatives to a blocked top. */
    private fun safePosition(level: ServerLevel, supply: BlockPos): Vec3? {
        val candidates = buildList {
            add(supply.above())
            for (radius in 1..3) {
                for (height in listOf(1, 0, -1, 2)) {
                    for (x in -radius..radius) {
                        for (z in -radius..radius) {
                            if (kotlin.math.max(kotlin.math.abs(x), kotlin.math.abs(z)) == radius) add(supply.offset(x, height, z))
                        }
                    }
                }
            }
        }
        return candidates.firstNotNullOfOrNull { DismountHelper.findSafeDismountLocation(EntityType.PLAYER, level, it, true) }
    }

    private fun tell(player: ServerPlayer, text: String) = player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY))
}
