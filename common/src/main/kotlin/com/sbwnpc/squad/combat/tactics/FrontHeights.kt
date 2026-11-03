package com.sbwnpc.squad.combat.tactics

import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

/**
 * Whether an attack's front has a rise its sniper or machine gunner could fight from (#262), read
 * off the terrain's heightmap: a column lookup each, no raycast. It only says where a height
 * is; the post on it is then searched as any height post ([TacticalJob.OVERWATCH]), with the
 * protection, line and clearance checks that search makes: a rise ahead of the line is no
 * assault, since no post nearer an enemy than his role's clearance is taken. Measured from
 * before contact, the front stood where its men did, and a hill between them and the enemy
 * was always "ahead".
 */
object FrontHeights {
    /** How far round the front's centre a rise is looked for. */
    const val RADIUS = 24

    /** A rise counts this many blocks above the front's centre. */
    const val RISE = 4

    private const val STEP = 4

    /** From a block's corner to its column's middle. */
    private const val MIDDLE = 0.5

    /** A column this near a [refused][rise] rise is part of it. */
    private const val REFUSED_RADIUS = 6.0

    /**
     * The highest column top within [RADIUS] of [front], at least [RISE] above it and not near a
     * [refused] rise, or null when the ground round it is flat.
     */
    fun rise(level: ServerLevel, front: Vec3, refused: Collection<Vec3> = emptyList()): Vec3? {
        var best: Vec3? = null
        for (dx in -RADIUS..RADIUS step STEP) {
            for (dz in -RADIUS..RADIUS step STEP) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue
                val x = Mth.floor(front.x) + dx
                val z = Mth.floor(front.z) + dz
                val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z).toDouble()
                if (top < front.y + RISE || (best != null && top <= best.y)) continue
                if (refused.any { hypot(it.x - (x + MIDDLE), it.z - (z + MIDDLE)) < REFUSED_RADIUS }) continue
                best = Vec3(x + MIDDLE, top, z + MIDDLE)
            }
        }
        return best
    }
}
