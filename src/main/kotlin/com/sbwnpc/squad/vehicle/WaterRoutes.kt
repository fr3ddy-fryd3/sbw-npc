package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue

/**
 * A way for a boat across the water, round headlands and along the bends of a river, to the bank
 * nearest where its crew is going.
 *
 * The ground pathfinder is no use here: it is for a man walking, who swims where he has to and
 * climbs out wherever. This searches the water surface the boat floats on, one column at a time,
 * wide enough everywhere for the hull, keeping off the banks where it can. The goal is usually on
 * land, so the search ends at the place a boat can put its crew ashore — water next to ground a
 * man can stand on — nearest the goal.
 *
 * Only loaded ground is searched, and within [MAX_NODES] columns; a goal farther than that gets the
 * landing nearest it among what was searched.
 */
object WaterRoutes {
    /** Columns searched at most. A river 500 blocks long is a few thousand; an open lake more. */
    private const val MAX_NODES = 30_000
    /** Nor farther than this from where the boat is. */
    private const val MAX_RANGE = 384
    /** Extra cost of a step right by the bank — a boat keeps to the middle where it can. */
    private const val BANK_PENALTY = 0.8
    /** A landing this near the goal is as good as any: the search stops there. */
    private const val CLOSE_ENOUGH = 4.0
    /** Columns searched since the best landing last got nearer the goal before giving up on a
     *  nearer one: a lake searched to its far shore has nothing more to offer. */
    private const val STALE_NODES = 6_000
    /** Straight stretches of the route are kept as one leg up to this many columns. */
    private const val MAX_LEG = 24
    private val DIAGONAL = Math.sqrt(2.0)

    /** [route] runs from the boat to [landing], a point on the water right by the bank. */
    class Route(val route: List<Vec3>, val landing: Vec3, val shore: Vec3, val length: Double)

    /**
     * The route for a boat [halfWidth] blocks either side of its middle, floating at [from], to the
     * landing nearest [goal]; null when it isn't afloat or no landing can be reached.
     */
    fun plan(level: ServerLevel, from: Vec3, halfWidth: Double, goal: Vec3): Route? {
        val surface = surfaceY(level, BlockPos.containing(from)) ?: return null
        val grid = Grid(level, surface, Math.max(0, Math.ceil(halfWidth - 0.5).toInt()))
        val startX = Math.floor(from.x).toInt()
        val startZ = Math.floor(from.z).toInt()
        val start = grid.nearestOpen(startX, startZ) ?: return null

        val g = HashMap<Long, Double>()
        val parent = HashMap<Long, Long>()
        val open = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        val key = { x: Int, z: Int -> BlockPos.asLong(x, 0, z) }
        val h = { x: Int, z: Int -> Math.hypot(x + 0.5 - goal.x, z + 0.5 - goal.z) }
        val startKey = key(start.first, start.second)
        g[startKey] = 0.0
        open.add(startKey to h(start.first, start.second))

        var best: Long? = null
        var bestShore: Vec3? = null
        var bestScore = Double.MAX_VALUE
        var expanded = 0
        var improvedAt = 0
        while (open.isNotEmpty() && expanded < MAX_NODES) {
            val (current, f) = open.poll()
            val cg = g[current] ?: continue
            if (f > cg + h(BlockPos.getX(current), BlockPos.getZ(current)) + 1e-6) continue // stale entry
            expanded++
            val x = BlockPos.getX(current)
            val z = BlockPos.getZ(current)
            grid.landingAt(x, z)?.let { shore ->
                val score = Math.hypot(shore.x - goal.x, shore.z - goal.z)
                if (score < bestScore - 0.5) improvedAt = expanded
                if (score < bestScore) {
                    bestScore = score
                    best = current
                    bestShore = shore
                }
            }
            if (bestScore <= CLOSE_ENOUGH) break
            if (best != null && expanded - improvedAt > STALE_NODES) break
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val nx = x + dx
                val nz = z + dz
                if (Math.abs(nx - start.first) > MAX_RANGE || Math.abs(nz - start.second) > MAX_RANGE) continue
                if (!grid.open(nx, nz)) continue
                // No cutting a corner across the bank.
                if (dx != 0 && dz != 0 && (!grid.open(x + dx, z) || !grid.open(x, z + dz))) continue
                val step = (if (dx != 0 && dz != 0) DIAGONAL else 1.0) + if (grid.byBank(nx, nz)) BANK_PENALTY else 0.0
                val ng = cg + step
                val nk = key(nx, nz)
                if (ng < (g[nk] ?: Double.MAX_VALUE)) {
                    g[nk] = ng
                    parent[nk] = current
                    open.add(nk to ng + h(nx, nz))
                }
            }
        }
        val end = best ?: return null
        val shore = bestShore ?: return null

        val columns = ArrayList<Long>()
        var at: Long? = end
        while (at != null) {
            columns += at
            at = parent[at]
        }
        columns.reverse()
        val y = surface + 0.5
        val points = simplify(grid, columns).map { Vec3(BlockPos.getX(it) + 0.5, y, BlockPos.getZ(it) + 0.5) }
        val landing = Vec3(BlockPos.getX(end) + 0.5, y, BlockPos.getZ(end) + 0.5)
        return Route(points, landing, shore, g[end] ?: 0.0)
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

    /** Keeps the turns of the route and drops the columns between them wherever the boat can run
     *  straight — up to [MAX_LEG] at a time, so a leg never runs far past a bend it can't see. */
    private fun simplify(grid: Grid, columns: List<Long>): List<Long> {
        if (columns.size <= 2) return columns
        val out = ArrayList<Long>()
        var i = 0
        out += columns[0]
        while (i < columns.size - 1) {
            var j = minOf(columns.size - 1, i + MAX_LEG)
            while (j > i + 1 && !grid.straight(columns[i], columns[j])) j--
            out += columns[j]
            i = j
        }
        return out
    }

    /** Which water columns at one surface height a hull fits through. */
    private class Grid(val level: ServerLevel, val y: Int, val clearance: Int) {
        private val water = HashMap<Long, Boolean>()
        private val fits = HashMap<Long, Boolean>()
        private val cursor = BlockPos.MutableBlockPos()

        /** Open water at the surface with room above it, on loaded ground. */
        fun water(x: Int, z: Int): Boolean = water.getOrPut(BlockPos.asLong(x, 0, z)) {
            if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) return@getOrPut false
            if (!level.getFluidState(cursor.set(x, y, z)).`is`(FluidTags.WATER)) return@getOrPut false
            for (dy in 1..2) {
                cursor.set(x, y + dy, z)
                if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty) return@getOrPut false
            }
            true
        }

        /** The whole hull fits with its middle over this column. */
        fun open(x: Int, z: Int): Boolean = fits.getOrPut(BlockPos.asLong(x, 0, z)) {
            for (dx in -clearance..clearance) for (dz in -clearance..clearance) {
                if (!water(x + dx, z + dz)) return@getOrPut false
            }
            true
        }

        fun byBank(x: Int, z: Int): Boolean =
            !open(x + 1, z) || !open(x - 1, z) || !open(x, z + 1) || !open(x, z - 1)

        /** Ground a man can step out onto beside this column, within the hull's reach and one more
         *  block — the bank the boat can come up against — or null. */
        fun landingAt(x: Int, z: Int): Vec3? {
            val reach = clearance + 1
            for ((dx, dz) in SIDES) {
                val sx = x + dx * reach
                val sz = z + dz * reach
                if (water(sx, sz)) continue
                if (level.chunkSource.getChunkNow(sx shr 4, sz shr 4) == null) continue
                val spot = Terrain.standableOrNull(level, sx + 0.5, y + 2.0, sz + 0.5, 4) ?: continue
                if (spot.y < y + 1 || spot.y > y + 2) continue
                if (level.getFluidState(BlockPos.containing(spot.x, spot.y - 1.0, spot.z)).`is`(FluidTags.WATER)) continue
                return spot
            }
            return null
        }

        /** A straight run between two columns is open all the way. */
        fun straight(a: Long, b: Long): Boolean {
            val ax = BlockPos.getX(a) + 0.5
            val az = BlockPos.getZ(a) + 0.5
            val bx = BlockPos.getX(b) + 0.5
            val bz = BlockPos.getZ(b) + 0.5
            val steps = Math.ceil(Math.hypot(bx - ax, bz - az) * 2).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                if (!open(Math.floor(ax + (bx - ax) * t).toInt(), Math.floor(az + (bz - az) * t).toInt())) return false
            }
            return true
        }

        /** The open column nearest ([x], [z]), within a few blocks — a boat at rest can sit over the
         *  edge of what counts as open. */
        fun nearestOpen(x: Int, z: Int): Pair<Int, Int>? {
            for (r in 0..3) for (dx in -r..r) for (dz in -r..r) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue
                if (open(x + dx, z + dz)) return (x + dx) to (z + dz)
            }
            return null
        }

        companion object {
            private val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        }
    }
}
