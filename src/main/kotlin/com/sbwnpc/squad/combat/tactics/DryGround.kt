package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Where a post planned by geometry alone goes when it fell on water: the nearest dry point a man
 * can stand on.
 *
 * Fronts and assault rings are laid out from distances to the enemy and know nothing of the
 * ground. In-game 16.10 an attack's firing line came out in the middle of a lake: every post
 * search there found no footing and the attackers stood where they were.
 */
object DryGround {
    /** How far from the planned point dry ground is looked for. */
    const val REACH = 32

    /**
     * [point] itself when a man can stand there (or its ground isn't loaded), else the nearest dry
     * footing; of those equally near, the one nearest [side], so a post in a lake moves to the
     * shore its man comes from rather than to the enemy's.
     */
    fun near(level: ServerLevel, point: Vec3, side: Vec3): Vec3 {
        if (!loaded(level, point) || Terrain.feetAt(level, point) != null) return point
        for (radius in 2..REACH step 2) {
            val steps = max(8, (PI * radius).toInt())
            val footing = (0 until steps).mapNotNull { index ->
                val angle = 2 * PI * index / steps
                val probe = Vec3(point.x + cos(angle) * radius, point.y, point.z + sin(angle) * radius)
                if (loaded(level, probe)) Terrain.feetAt(level, probe) else null
            }
            footing.minByOrNull { it.distanceToSqr(side.x, it.y, side.z) }?.let { return it }
        }
        return point
    }

    private fun loaded(level: ServerLevel, point: Vec3) = BlockPos.containing(point).let { level.chunkSource.hasChunk(it.x shr 4, it.z shr 4) }
}
