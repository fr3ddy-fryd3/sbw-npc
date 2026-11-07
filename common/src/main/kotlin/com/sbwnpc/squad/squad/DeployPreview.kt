package com.sbwnpc.squad.squad

import com.sbwnpc.squad.item.SquadToolItem
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * Where a deployment would land (#208): a column of particles at the spot a plain right-click would
 * deploy on, shown while the Squad Tool is held in Recruit mode. Sent only to that player through the
 * targeted-particle overload, like [SelectionHighlight], and with the limiter override so the column
 * shows beyond vanilla's 32 blocks, up to [SquadToolItem.DEPLOY_RANGE].
 */
object DeployPreview {
    private const val INTERVAL_TICKS = 4
    private const val COLUMN_HEIGHT = 4
    private const val FIRST_PARTICLE_HEIGHT = 0.25
    private const val PARTICLE_SIZE = 1.0f
    private const val WHITE = 0xFFFFFF

    /** Whether [player] sees the preview: the tool in a hand in Recruit mode, not sneaking (that opens the config) and not drawing a route. */
    fun shows(player: ServerPlayer): Boolean = !player.isShiftKeyDown && !RouteRecording.isRecording(player.uuid) && InteractionHand.entries.any { recruitTool(player.getItemInHand(it)) }

    private fun recruitTool(stack: ItemStack) = stack.item is SquadToolItem && SquadToolItem.mode(stack) == SquadToolItem.MODE_RECRUIT

    fun tick(server: MinecraftServer) {
        if (server.tickCount % INTERVAL_TICKS != 0) return
        for (player in server.playerList.players) {
            if (!shows(player)) continue
            val level = player.level() as? ServerLevel ?: continue
            val spot = SquadToolItem.deployTarget(player) ?: continue
            val tool = InteractionHand.entries.map { player.getItemInHand(it) }.first(::recruitTool)
            val color = SquadToolItem.readConfig(tool)?.faction?.rgb ?: WHITE
            val options = DustParticleOptions(Vec3.fromRGB24(color).toVector3f(), PARTICLE_SIZE)
            val base = Vec3.atBottomCenterOf(spot)
            for (step in 0 until COLUMN_HEIGHT) {
                level.sendParticles(player, options, true, base.x, base.y + FIRST_PARTICLE_HEIGHT + step, base.z, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }
}
