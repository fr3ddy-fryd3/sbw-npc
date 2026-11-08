package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.tactics.Shelter
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Finds a spot under a roof near a shelled man (owner 08.11, [Shelter]). Budgeted: at most [MAX_COLUMNS]
 * columns on a 2-block grid within [Shelter.RADIUS], one search per man every [REFRESH_TICKS], and a
 * walking route checked only for the [ROUTE_CHECKS] nearest roofed spots. The result is kept until
 * the next search.
 */
internal class RoofSearch {
    private val found = HashMap<UUID, Pair<Long, Point3?>>()

    fun near(npc: NpcEntity, now: Long): Point3? {
        val last = found[npc.uuid]
        last?.takeIf { now - it.first < REFRESH_TICKS + npc.uuid.hashCode().mod(STAGGER) }?.let { return it.second }
        val level = npc.level()
        val origin = npc.blockPosition()
        // The spot already found stands while it is still roofed and near: a new nearest one each
        // refresh moved his post as he walked.
        last?.second?.let { kept ->
            val pos = BlockPos.containing(kept.x, kept.y, kept.z)
            if (pos.distSqr(origin) <= Shelter.RADIUS * Shelter.RADIUS && roofed(level, pos)) {
                found[npc.uuid] = now to kept
                return kept
            }
        }
        val columns = ArrayList<BlockPos>()
        for (dx in -Shelter.RADIUS..Shelter.RADIUS step 2) {
            for (dz in -Shelter.RADIUS..Shelter.RADIUS step 2) {
                if (dx * dx + dz * dz <= Shelter.RADIUS * Shelter.RADIUS) columns += origin.offset(dx, 0, dz)
            }
        }
        // Where he stands counts too: under a roof already, he stays.
        val roofed = (listOf(origin) + columns.sortedBy { it.distSqr(origin) }).take(MAX_COLUMNS).mapNotNull { standing(level, it) }.filter { roofed(level, it) }
        val spot = roofed.take(ROUTE_CHECKS).firstOrNull { it == origin || npc.navigation.createPath(it, 0)?.canReach() == true }
        val point = spot?.let { Point3(it.x + HALF, it.y.toDouble(), it.z + HALF) }
        found[npc.uuid] = now to point
        DebugFlags.log(LogGroup.ORDER, "[shelter] actor={} spot={} roofed={} reason=shelled_unseen", npc.uuid, point, roofed.size)
        return point
    }

    fun keep(wanted: (UUID) -> Boolean) {
        found.keys.retainAll(wanted)
    }

    fun clear() = found.clear()

    companion object {
        private const val MAX_COLUMNS = 400
        private const val REFRESH_TICKS = 40L
        private const val ROUTE_CHECKS = 3
        private const val STEP = 3
        private const val HALF = 0.5
        private const val STAGGER = 20

        /** The standing spot in [column] within [STEP] blocks of its height: ground below, room for him. */
        private fun standing(level: Level, column: BlockPos): BlockPos? =
            (0..STEP).flatMap { listOf(it, -it) }.distinct().map { column.above(it) }.firstOrNull { feet ->
                level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP) &&
                    level.getBlockState(feet).getCollisionShape(level, feet).isEmpty &&
                    level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty
            }

        /** Whether a man standing at [feet] has a roof over him ([Shelter.covered]). */
        fun roofed(level: Level, feet: BlockPos): Boolean =
            Shelter.covered(
                (0..Shelter.ROOF_MAX).map { height ->
                    val state = level.getBlockState(feet.above(height))
                    val at = feet.above(height)
                    (!state.getCollisionShape(level, at).isEmpty) to state.`is`(BlockTags.LEAVES)
                },
            )
    }
}
