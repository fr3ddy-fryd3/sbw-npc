package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Where a post planned by geometry alone goes: onto the ground at its spot, or, when it fell on
 * water, the nearest dry point a man can stand on.
 *
 * Fronts and assault rings are laid out from distances to the enemy and know nothing of the
 * ground. In-game 16.10 an attack's firing line came out in the middle of a lake: every post
 * search there found no footing and the attackers stood where they were. On a slope the line came
 * out at the objective's height, metres above or below the ground, and every probe round it
 * failed on `terrain` (#55).
 */
object DryGround {
    /** How far from the planned point dry ground is looked for. */
    const val REACH = 32

    /** How far up or down a planned point is followed to its footing. */
    private const val SCAN = 32

    /** Half a block below the feet is inside the block they stand on, slab or full. */
    private const val HALF_BLOCK = 0.5

    /** How far the other way it is followed when that finds nothing. */
    private const val BACK_SCAN = 12

    /**
     * [point] moved up or down onto the footing at its spot (or left as it is when its ground isn't
     * loaded), else the nearest dry footing; of those equally near, the one nearest [side], so a
     * post in a lake moves to the shore its man comes from rather than to the enemy's.
     */
    fun near(level: ServerLevel, point: Vec3, side: Vec3): Vec3 {
        if (!loaded(level, point)) return point
        footing(level, point)?.let { return Vec3(point.x, it.y, point.z) }
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

    /**
     * The footing at [point]'s spot with room for a man: below it when it hangs in the air, above
     * it when it lies inside the ground, then the other way. Leaves are not footing, so a post over
     * woodland goes to the ground rather than onto the canopy.
     */
    private fun footing(level: ServerLevel, point: Vec3): Vec3? {
        val first = if (room(level, point)) -1 else 1
        return scan(level, point, first, SCAN) ?: scan(level, point, -first, BACK_SCAN)
    }

    private fun scan(level: ServerLevel, point: Vec3, step: Int, range: Int): Vec3? =
        (0..range).firstNotNullOfOrNull { offset ->
            Terrain.feetAt(level, point.add(0.0, (step * offset).toDouble(), 0.0), guard = 0) { feet ->
                room(level, feet) && !level.getBlockState(BlockPos.containing(feet.x, feet.y - HALF_BLOCK, feet.z)).`is`(BlockTags.LEAVES)
            }
        }

    /** Whether a man's body fits with his feet at [feet]. */
    private fun room(level: ServerLevel, feet: Vec3): Boolean {
        val base = BlockPos.containing(feet.x, Mth.ceil(feet.y).toDouble(), feet.z)
        return (0..1).all { base.above(it).let { block -> level.getBlockState(block).getCollisionShape(level, block).isEmpty } }
    }

    private fun loaded(level: ServerLevel, point: Vec3) = BlockPos.containing(point).let { level.chunkSource.hasChunk(it.x shr 4, it.z shr 4) }
}
