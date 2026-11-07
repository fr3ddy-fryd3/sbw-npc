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

    /** A chunk's size as a shift and as a mask of block coordinates. */
    private const val CHUNK_SHIFT = 4
    private const val CHUNK_MASK = 15

    /** A man walks up one block at a step; a column more than this above the last is a wall. */
    private const val CLIMB = 1

    /**
     * The highest column top within [RADIUS] of [front], at least [RISE] above it, a man can walk
     * up to from [front] ([climbable]) and not near a [refused] rise, or null when the ground round
     * it is flat. Columns in chunks not loaded are skipped: reading them would load them.
     */
    fun rise(level: ServerLevel, front: Vec3, refused: Collection<Vec3> = emptyList()): Vec3? =
        columns(front)
            .mapNotNull { (x, z) -> top(level, x, z)?.let { Vec3(x + MIDDLE, it.toDouble(), z + MIDDLE) } }
            .filter { it.y >= front.y + RISE && refused.none { r -> hypot(r.x - it.x, r.z - it.z) < REFUSED_RADIUS } }
            // Highest first, so the walk up is checked only until one qualifies.
            .sortedByDescending { it.y }
            .firstOrNull { climbable(level, front, Mth.floor(it.x), Mth.floor(it.z)) }

    /** The columns sampled round [front], every [STEP] blocks within [RADIUS]. */
    private fun columns(front: Vec3): Sequence<Pair<Int, Int>> =
        sequence {
            for (dx in -RADIUS..RADIUS step STEP) {
                for (dz in -RADIUS..RADIUS step STEP) {
                    if (dx * dx + dz * dz <= RADIUS * RADIUS) yield(Mth.floor(front.x) + dx to Mth.floor(front.z) + dz)
                }
            }
        }

    /**
     * Whether the ground from [front] straight to column ([x], [z]) never rises more than [CLIMB]
     * in a block. A roof or a sheer wall tops the heightmap too, and the height men walked to
     * its foot again and again as each column of it was offered and refused in turn.
     */
    private fun climbable(level: ServerLevel, front: Vec3, x: Int, z: Int): Boolean {
        val fx = Mth.floor(front.x)
        val fz = Mth.floor(front.z)
        val steps = maxOf(kotlin.math.abs(x - fx), kotlin.math.abs(z - fz))
        var last = top(level, fx, fz) ?: return false
        for (i in 1..steps) {
            val here = top(level, fx + Math.round((x - fx) * i / steps.toDouble()).toInt(), fz + Math.round((z - fz) * i / steps.toDouble()).toInt()) ?: return false
            if (here - last > CLIMB) return false
            last = here
        }
        return true
    }

    /** The first free block above column ([x], [z]), or null when its chunk is not loaded. */
    private fun top(level: ServerLevel, x: Int, z: Int): Int? = level.chunkSource.getChunkNow(x shr CHUNK_SHIFT, z shr CHUNK_SHIFT)?.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and CHUNK_MASK, z and CHUNK_MASK)?.plus(1)
}
