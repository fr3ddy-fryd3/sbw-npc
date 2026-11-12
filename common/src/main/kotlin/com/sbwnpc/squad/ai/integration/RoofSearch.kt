package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.tactics.Shelter
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.hypot

/**
 * Finds a spot under a roof near a shelled man (owner 08.11, [Shelter]). Budgeted: at most [MAX_COLUMNS]
 * columns on a 2-block grid within [Shelter.RADIUS], one search per man every [REFRESH_TICKS], and a
 * walking route checked only for the [ROUTE_CHECKS] nearest roofed spots. The result is kept until
 * the next search.
 */
internal class RoofSearch {
    private val found = HashMap<UUID, Pair<Long, Point3?>>()

    fun near(npc: NpcEntity, now: Long, failed: Point3? = null): Point3? {
        val last = found[npc.uuid]
        last?.takeIf { now - it.first < REFRESH_TICKS + npc.uuid.hashCode().mod(STAGGER) && !near(it.second, failed) }?.let { return it.second }
        val level = npc.level()
        val origin = npc.blockPosition()
        val taken by lazy { taken(npc, now) }
        val free = { feet: BlockPos -> !FiringSpots.crowded(Vec3.atBottomCenterOf(feet), taken) && !near(Point3(feet.x + HALF, feet.y.toDouble(), feet.z + HALF), failed) }
        // The spot already found stands while it is still roofed and near: a new nearest one each
        // refresh moved his post as he walked.
        last?.second?.let { kept ->
            val pos = BlockPos.containing(kept.x, kept.y, kept.z)
            if (keeps(npc, pos, origin) && (sheltering(npc) || free(pos))) {
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
        val roofed = (listOf(origin) + columns.sortedBy { it.distSqr(origin) }).take(MAX_COLUMNS).mapNotNull { column -> standing(level, column).firstOrNull { roofed(level, it) } }.filter { it == origin || free(it) }
        val spot = roofed.take(ROUTE_CHECKS).firstOrNull { it == origin || npc.navigation.createPath(it, 0)?.canReach() == true }
        val point = spot?.let { Point3(it.x + HALF, it.y.toDouble(), it.z + HALF) }
        found[npc.uuid] = now to point
        DebugFlags.log(LogGroup.ORDER, "[shelter] actor={} spot={} roofed={} reason=shelled_unseen", npc.uuid, point, roofed.size)
        return point
    }

    /** Whether [npc] stands at the roof spot last found for him. */
    fun sheltering(npc: NpcEntity): Boolean {
        val spot = found[npc.uuid]?.second ?: return false
        return npc.position().distanceToSqr(spot.x, spot.y, spot.z) <= AT_SPOT * AT_SPOT
    }

    /**
     * Where others stand, hold a post or are headed under a roof. The post search under a roof keeps
     * the same spacing, so a spot offered here past it would only fail there.
     */
    private fun taken(npc: NpcEntity, now: Long): List<Vec3> {
        val result = ArrayList(FiringSpots.nearbyWithBodies(npc.level() as ServerLevel, npc, Shelter.RADIUS.toDouble()))
        for ((id, pick) in found) {
            val spot = pick.second
            if (id != npc.uuid && spot != null && now - pick.first < HELD_TICKS) result += Vec3(spot.x, spot.y, spot.z)
        }
        return result
    }

    // Still near him and still roofed; under it already, a squadmate passing by does not take it from him.
    private fun keeps(npc: NpcEntity, pos: BlockPos, origin: BlockPos) = pos.distSqr(origin) <= Shelter.RADIUS * Shelter.RADIUS && roofed(npc.level(), pos)

    private fun near(spot: Point3?, failed: Point3?) = spot != null && failed != null && hypot(spot.x - failed.x, spot.z - failed.z) <= Shelter.FAILED_NEAR

    fun keep(wanted: (UUID) -> Boolean) {
        found.keys.retainAll(wanted)
    }

    fun clear() = found.clear()

    companion object {
        private const val MAX_COLUMNS = 400
        private const val AT_SPOT = 2.0
        private const val REFRESH_TICKS = 40L
        private const val ROUTE_CHECKS = 3
        private const val STEP = 3
        private const val HALF = 0.5
        private const val STAGGER = 20

        // Another man's roof pick counts as his for this long, while he walks there and claims it.
        private const val HELD_TICKS = 200L

        /**
         * The standing spots in [column] within [STEP] blocks of its height, nearest first: ground
         * below, room for him. All of them, not the nearest alone: from a tower the nearest floor in a
         * shed's column is its roof top, and no sentry found the floor under it.
         */
        private fun standing(level: Level, column: BlockPos): Sequence<BlockPos> =
            (0..STEP).asSequence().flatMap { sequenceOf(it, -it) }.distinct().map { column.above(it) }.filter { feet ->
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
