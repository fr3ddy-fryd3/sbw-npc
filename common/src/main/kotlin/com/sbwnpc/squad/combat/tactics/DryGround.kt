package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
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

    /** How far up or down a planned point is followed before the heightmap answers instead. */
    private const val SCAN = 32

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
                if (loaded(level, probe)) footing(level, probe) else null
            }
            footing.minByOrNull { it.distanceToSqr(side.x, it.y, side.z) }?.let { return it }
        }
        return point
    }

    /**
     * The ground at [point]'s spot when a man can stand on it: the first surface below a point in
     * the air, the top of the ground a point lies inside. Leaves are passed through, so a post over
     * woodland goes to the ground rather than onto the canopy. Water is no footing and stops the
     * walk, so a lake post goes to the shore and not to a cave under the lakebed.
     */
    private fun footing(level: ServerLevel, point: Vec3): Vec3? {
        var block = BlockPos.containing(point)
        val inAir = passable(level, block)
        repeat(SCAN) {
            if (inAir) {
                val below = block.below()
                if (!passable(level, below)) return surface(level, below)
                block = below
            } else {
                val above = block.above()
                if (passable(level, above)) return surface(level, block)
                block = above
            }
        }
        // Farther off than the walk: a point planned at a target's height far above the ground.
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, block.x, block.z) - 1
        return surface(level, BlockPos(block.x, top, block.z))
    }

    private fun passable(level: ServerLevel, block: BlockPos): Boolean {
        val state = level.getBlockState(block)
        return state.fluidState.isEmpty && (state.getCollisionShape(level, block).isEmpty || state.`is`(BlockTags.LEAVES))
    }

    /** The feet on top of [floor], when it is dry ground with room for a man above it. */
    private fun surface(level: ServerLevel, floor: BlockPos): Vec3? {
        val state = level.getBlockState(floor)
        if (!state.fluidState.isEmpty || state.`is`(BlockTags.LEAVES)) return null
        return Terrain.feetAt(level, Vec3.atCenterOf(floor), guard = 0) { room(level, it) }
    }

    /** Whether a man's body fits with his feet at [feet]. */
    private fun room(level: ServerLevel, feet: Vec3): Boolean {
        val base = BlockPos.containing(feet.x, Mth.ceil(feet.y).toDouble(), feet.z)
        return (0..1).all { base.above(it).let { block -> level.getBlockState(block).getCollisionShape(level, block).isEmpty } }
    }

    private fun loaded(level: ServerLevel, point: Vec3) = BlockPos.containing(point).let { level.chunkSource.hasChunk(it.x shr 4, it.z shr 4) }
}
