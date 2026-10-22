package com.sbwnpc.squad.squad

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * Keeps FOLLOW squads on their owner: refreshes the point they make for and which way he is
 * heading, and stands them down on DEFEND where they are when he is gone.
 */
object FollowOrders {
    private const val INTERVAL_TICKS = 10

    /** The squad's objective follows the player only once he is this far from it, so its plans are not redone every step. */
    private const val OBJECTIVE_STEP = 4.0

    /** Shorter than this the player is standing still and has no heading. */
    private const val MIN_STEP = 0.4

    fun tick(server: MinecraftServer) {
        if (server.tickCount % INTERVAL_TICKS != 0) return
        val mgr = SquadManager.get(server)
        for (squad in mgr.all().filter { it.order == SquadOrder.FOLLOW }) {
            val player = SquadManager.findEntity(server, squad.owner) as? ServerPlayer
            if (player != null && stillWithOwner(server, squad, player)) {
                follow(mgr, squad, player)
            } else {
                // The owner left or died, or his men are in another dimension: hold where they stand.
                val level = player?.serverLevel() ?: squad.members.firstNotNullOfOrNull { SquadManager.findEntity(server, it) }?.level() as? ServerLevel
                level?.let { mgr.stopFollowing(it, squad) }
            }
        }
    }

    private fun stillWithOwner(server: MinecraftServer, squad: Squad, player: ServerPlayer): Boolean {
        if (!player.isAlive || player.isSpectator) return false
        val home = squad.members.firstNotNullOfOrNull { SquadManager.findEntity(server, it) }?.level()
        return home == null || home === player.level()
    }

    private fun follow(mgr: SquadManager, squad: Squad, player: ServerPlayer) {
        if (squad.focusEntity != player.uuid) mgr.setFocus(squad.id, player.uuid)
        val here = player.position()
        squad.followLast?.let { last ->
            val step = Vec3(here.x - last.x, 0.0, here.z - last.z)
            squad.followHeading = if (step.length() >= MIN_STEP) step.normalize() else Vec3.ZERO
        }
        squad.followLast = here
        val objective = squad.objective
        if (objective == null || objective.center.distanceTo(here) >= OBJECTIVE_STEP) {
            // Not setObjective: that restarts the command, and this point only trails the player.
            squad.objective = player.blockPosition()
            squad.objectiveDimension = player.serverLevel().dimension()
            mgr.setDirty()
        }
    }
}
