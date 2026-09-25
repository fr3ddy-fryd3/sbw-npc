package com.sbwnpc.squad.vehicle

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Steers a driven vehicle round the trees in its way.
 *
 * The route a driver follows comes from its own, man-sized pathfinder, which happily threads a gap
 * between two trunks that a tank is three times too wide for; and waypoints are taken well ahead,
 * so the line to the next one cuts corners through whatever is in between. This looks down the
 * vehicle's own width for a few blocks ahead and, when a trunk or a canopy is there, picks the
 * nearest heading either side that is clear.
 *
 * Only trees count — logs and leaves. Anything else in the way is terrain: a hillside that rises
 * ahead is something to drive up, not round, and the stuck recovery already deals with walls.
 */
object TreeAvoidance {
    /** Tried in order, degrees off the wanted heading: straight on first, then widening both ways. */
    private val OFFSETS = doubleArrayOf(0.0, 20.0, -20.0, 40.0, -40.0, 60.0, -60.0, 85.0, -85.0)
    private const val LOOKAHEAD = 8.0
    private const val PROBE_STEP = 2.0
    /** Ground clutter below this is driven over. */
    private const val CLEARANCE_FROM_FLOOR = 0.5
    private const val SIDE_SLACK = 0.2

    /** Where to steer instead of [toward], or [toward] itself when the way there is clear (or
     *  nothing nearby is). */
    fun steerPoint(level: Level, vehicle: Entity, toward: Vec3): Vec3 {
        val here = vehicle.position()
        val want = Vec3(toward.x - here.x, 0.0, toward.z - here.z)
        if (want.lengthSqr() < 1.0e-4) return toward
        val base = Math.atan2(want.z, want.x)
        for (offset in OFFSETS) {
            val angle = base + Math.toRadians(offset)
            val dir = Vec3(Math.cos(angle), 0.0, Math.sin(angle))
            if (!treeAhead(level, vehicle.boundingBox, dir)) {
                return if (offset == 0.0) toward else here.add(dir.scale(LOOKAHEAD))
            }
        }
        return toward
    }

    private fun treeAhead(level: Level, body: AABB, dir: Vec3): Boolean {
        val probe = AABB(
            body.minX + SIDE_SLACK, body.minY + CLEARANCE_FROM_FLOOR, body.minZ + SIDE_SLACK,
            body.maxX - SIDE_SLACK, body.maxY - 0.01, body.maxZ - SIDE_SLACK
        )
        var d = PROBE_STEP
        while (d <= LOOKAHEAD) {
            if (hasTree(level, probe.move(dir.x * d, 0.0, dir.z * d))) return true
            d += PROBE_STEP
        }
        return false
    }

    private fun hasTree(level: Level, box: AABB): Boolean {
        val cursor = BlockPos.MutableBlockPos()
        for (x in Math.floor(box.minX).toInt()..Math.floor(box.maxX).toInt()) {
            for (z in Math.floor(box.minZ).toInt()..Math.floor(box.maxZ).toInt()) {
                for (y in Math.floor(box.minY).toInt()..Math.floor(box.maxY).toInt()) {
                    val state = level.getBlockState(cursor.set(x, y, z))
                    if (state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES)) return true
                }
            }
        }
        return false
    }
}
