package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.route.CellPlanner
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.phys.Vec3

/**
 * A boat's way over the water to the bank its crew gets off at — [CellPlanner] over the water
 * surface the boat floats on, as last seen ([WaterMap]).
 *
 * The water is open where the whole hull fits over it; a way keeps to the middle, off the banks
 * where it can — a boat at speed needs fifty blocks to turn. A trip ends at a bank, water next to
 * ground a man can step out onto, and the crew walks in from there; where the water runs on out
 * of sight it is taken to go on toward the goal.
 */
object WaterRoutes {
    /** A boat covers ground this many times faster than a man walking. */
    const val BOAT_PACE = 2.5
    /** Extra cost of a column with the bank within two blocks of the hull — the middle is kept to. */
    private const val NARROW_PENALTY = 1.5
    /** Extra cost of a column right by the bank. */
    private const val BANK_PENALTY = 3.0

    /**
     * A search for the way of a boat [halfWidth] blocks either side of its middle, floating at
     * [from], to where its crew is best set down for [goal]; null when it isn't afloat.
     */
    fun search(level: ServerLevel, from: Vec3, halfWidth: Double, goal: Vec3): CellPlanner.Search? {
        val surface = surfaceY(level, BlockPos.containing(from)) ?: return null
        return CellPlanner.search(Water(level, surface, Math.max(0, Math.ceil(halfWidth - 0.5).toInt())), from, goal)
    }

    /**
     * Ground a man can step out onto within [reach] blocks of [hull]'s sides — a boat that has
     * come to rest against the bank, however badly it parked.
     */
    fun bankBeside(level: ServerLevel, hull: net.minecraft.world.phys.AABB, reach: Int): Boolean {
        val y = hull.minY + 1.0
        for (ring in 1..reach) {
            val box = hull.inflate(ring.toDouble(), 0.0, ring.toDouble())
            var x = box.minX
            while (x <= box.maxX) {
                if (dryAt(level, x, y, box.minZ) || dryAt(level, x, y, box.maxZ)) return true
                x += 1.0
            }
            var z = box.minZ
            while (z <= box.maxZ) {
                if (dryAt(level, box.minX, y, z) || dryAt(level, box.maxX, y, z)) return true
                z += 1.0
            }
        }
        return false
    }

    private fun dryAt(level: ServerLevel, x: Double, y: Double, z: Double): Boolean {
        val spot = Terrain.standableOrNull(level, x, y + 1.0, z, 4) ?: return false
        if (spot.y > y + 2.0) return false
        val under = BlockPos.containing(spot.x, spot.y - 0.5, spot.z)
        return !level.getFluidState(under).`is`(FluidTags.WATER) && !level.getFluidState(BlockPos.containing(spot)).`is`(FluidTags.WATER)
    }

    /** The water surface at or near [at]: the top water block with no water over it. */
    fun surfaceY(level: ServerLevel, at: BlockPos): Int? {
        for (dy in 1 downTo -3) {
            val p = at.offset(0, dy, 0)
            if (level.getFluidState(p).`is`(FluidTags.WATER) && !level.getFluidState(p.above()).`is`(FluidTags.WATER)) return p.y
        }
        return null
    }

    /** The water surface at one height, as a boat with [clearance] blocks either side of its middle sees it. */
    private class Water(val level: ServerLevel, val y: Int, val clearance: Int) : CellPlanner.Medium {
        private val water = HashMap<Long, Boolean>()
        private val fits = HashMap<Long, Boolean>()

        override val pace = 1.0 / BOAT_PACE

        override fun known(x: Int, z: Int): Boolean = WaterMap.known(level, y, x, z)

        /** Open water at the surface with room above it, as seen now or last seen. */
        private fun water(x: Int, z: Int): Boolean = water.getOrPut(BlockPos.asLong(x, 0, z)) {
            WaterMap.kind(level, y, x, z) == WaterMap.Kind.WATER
        }

        /** The whole hull fits with its middle over this column. */
        override fun open(x: Int, z: Int): Boolean = fits.getOrPut(BlockPos.asLong(x, 0, z)) {
            for (dx in -clearance..clearance) for (dz in -clearance..clearance) {
                if (!water(x + dx, z + dz)) return@getOrPut false
            }
            true
        }

        override fun step(ax: Int, az: Int, bx: Int, bz: Int) = true

        override fun extraCost(x: Int, z: Int): Double = when {
            !open(x + 1, z) || !open(x - 1, z) || !open(x, z + 1) || !open(x, z - 1) -> BANK_PENALTY
            !wide(x, z) -> NARROW_PENALTY
            else -> 0.0
        }

        /** Two blocks of water to spare either side of the hull as well. */
        private fun wide(x: Int, z: Int): Boolean =
            open(x + 2, z) && open(x - 2, z) && open(x, z + 2) && open(x, z - 2) &&
                open(x + 2, z + 2) && open(x - 2, z - 2) && open(x + 2, z - 2) && open(x - 2, z + 2)

        /** Ground a man can step out onto beside this column, within the hull's reach and one more
         *  block — the bank the boat can come up against — or null. */
        override fun exitAt(x: Int, z: Int, goal: Vec3): Vec3? {
            val reach = clearance + 1
            for ((dx, dz) in SIDES) {
                val sx = x + dx * reach
                val sz = z + dz * reach
                when (WaterMap.kind(level, y, sx, sz)) {
                    WaterMap.Kind.SHORE_LOW -> return Vec3(sx + 0.5, y + 1.0, sz + 0.5)
                    WaterMap.Kind.SHORE_HIGH -> return Vec3(sx + 0.5, y + 2.0, sz + 0.5)
                    else -> continue
                }
            }
            return null
        }

        override fun pointY(x: Int, z: Int) = y + 0.5

        companion object {
            private val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        }
    }
}
