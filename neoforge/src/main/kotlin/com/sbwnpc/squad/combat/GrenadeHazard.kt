package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.GrenadeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * Where a live grenade is about to go off, for anything choosing a spot to stand on.
 *
 * Running from a grenade is [com.sbwnpc.squad.entity.ai.GrenadeEvadeBehaviour]'s job; this is the
 * other half. A firing position or a piece of cover chosen inside the blast walks the NPC straight
 * back into it — or has it running out and back in, which is the twitching it looked like in-game.
 * Grenades still in the air count too: they come down somewhere near where they're headed, and a
 * spot next to one is no better for its not having landed yet.
 */
object GrenadeHazard {
    /** Blast plus a margin for fragments and for where a rolling grenade actually stops. */
    val dangerRadius: Double get() = Ports.grenades.blastRadius + SAFETY_MARGIN

    fun threatens(level: ServerLevel, pos: Vec3): Boolean {
        val grenades = GrenadeRegistry.all(level)
        if (grenades.isEmpty()) return false
        val r2 = dangerRadius * dangerRadius
        return grenades.any { it.isAlive && it.position().distanceToSqr(pos) <= r2 }
    }

    fun threatens(level: ServerLevel, pos: BlockPos): Boolean = threatens(level, pos.bottomCenter)

    private const val SAFETY_MARGIN = 2.0
}
