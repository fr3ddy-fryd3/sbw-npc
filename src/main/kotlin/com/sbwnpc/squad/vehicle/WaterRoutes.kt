package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue

/**
 * A way for a boat across the water, round headlands and along the bends of a river, to where its
 * crew gets off for the goal.
 *
 * The ground pathfinder is no use here: it is for a man walking, who swims where he has to and
 * climbs out wherever. This searches the water surface the boat floats on, wide enough everywhere
 * for the hull, and ends at a bank — water next to ground a man can stand on.
 *
 * Which bank is a matter of the whole trip's cost, in blocks walked: the voyage at [BOAT_PACE]
 * times a man's pace, then the walk in from the bank. The water is taken in [CELL]-block cells,
 * visited outward from the boat nearest first ([Search] runs a few hundred a tick), and the search
 * stops once sailing on further would cost more than the best trip found — nothing further out
 * could beat it.
 *
 * The world beyond the ground that's loaded, and past [MAX_RANGE], is unknown. Where the water runs
 * on out of sight it is taken to go on toward the goal ([UNSEEN_WATER]), and going on over it is
 * a trip like any other: the planner's usual assumption of free space where nothing is known yet.
 * Deciding on a bank as if the water ended where the loaded ground did set crews down hundreds of
 * blocks short with the lake running on. When going on is the cheaper trip the boat sails to the
 * edge of what's seen, the ground loads round it, and it looks again — landing then if it turns
 * out there was no more water.
 *
 * Then the way itself is found column by column, only through the cells the first pass went by,
 * keeping to the middle of the water where it can — a boat at speed needs fifty blocks to turn.
 */
object WaterRoutes {
    /** Blocks along a side of a cell of the first pass. */
    private const val CELL = 4
    /** Cells visited at most. */
    private const val MAX_CELLS = 80_000
    /** Columns of the second pass at most. */
    private const val MAX_COLUMNS = 60_000
    /** Nor farther than this from where the boat is. */
    private const val MAX_RANGE = 768
    /** Extra cost of a column with the bank within two blocks of the hull — the middle is kept to. */
    private const val NARROW_PENALTY = 1.5
    /** Extra cost of a column right by the bank. */
    private const val BANK_PENALTY = 3.0
    /** A boat covers ground this many times faster than a man walking. */
    const val BOAT_PACE = 2.5
    /**
     * Water not yet seen — past the ground that's loaded, or the search's range — is taken to go
     * on toward the goal, at this many times the straight line. It's a guess, made good as the boat
     * gets there and the ground loads: there it looks again, and lands if it was wrong.
     */
    const val UNSEEN_WATER = 1.3
    /** Straight stretches of the route are kept as one leg up to this many columns. */
    private const val MAX_LEG = 24
    /** Cells of the first pass counted as this many units of a tick's search budget. */
    private const val CELL_COST = 12
    private val DIAGONAL = Math.sqrt(2.0)

    /**
     * [route] runs from the boat to [landing], a point on the water right by the bank at [shore].
     * Not [complete] when the search ran out before the water did — it went on nearer the goal
     * than any bank found — and then [landing] is as far that way as it got, with no bank at it:
     * the boat sails there and looks again.
     */
    class Route(val route: List<Vec3>, val landing: Vec3, val shore: Vec3, val length: Double, val complete: Boolean) {
        /** What's left after the voyage, in blocks walked: the walk in from the bank, or the rest of
         *  the way over the water not yet seen. */
        fun remaining(goal: Vec3): Double {
            val d = Math.hypot(shore.x - goal.x, shore.z - goal.z)
            return if (complete) d else d * UNSEEN_WATER / BOAT_PACE
        }
    }

    /**
     * A search for the way of a boat [halfWidth] blocks either side of its middle, floating at
     * [from], to the landing nearest [goal]; null when it isn't afloat.
     */
    fun search(level: ServerLevel, from: Vec3, halfWidth: Double, goal: Vec3): Search? {
        val surface = surfaceY(level, BlockPos.containing(from)) ?: return null
        val grid = Grid(level, surface, Math.max(0, Math.ceil(halfWidth - 0.5).toInt()))
        val start = grid.nearestOpen(Math.floor(from.x).toInt(), Math.floor(from.z).toInt()) ?: return null
        return Search(grid, start, goal)
    }

    /** One search under way — see [step] and [result]. */
    class Search internal constructor(private val grid: Grid, private val start: Pair<Int, Int>, private val goal: Vec3) {
        // First pass: cells, outward from the boat.
        private val startCell = cellOf(start.first, start.second)
        private val cellParent = HashMap<Long, Long>()
        private val cellSeen = HashSet<Long>()
        private val cellQueue = ArrayDeque<Long>()
        /** Cells from the boat, for each cell reached — the queue is in this order. */
        private val depth = HashMap<Long, Int>()
        /**
         * The best way to end the voyage so far, and what the whole trip costs in blocks walked:
         * a bank (sail there, walk in) or the edge of the water seen (sail there, and on over the
         * water not yet seen). [bestColumn] is the water column it ends at.
         */
        private var bestCost = Double.MAX_VALUE
        private var bestCell: Long? = null
        private var bestColumn: Long? = null
        private var bestShore: Vec3? = null
        private var bestIsEdge = false
        private var cellsVisited = 0

        // Second pass: columns, through the cells the way goes by.
        private var corridor: HashSet<Long>? = null
        private var target: Long? = null
        private var onward = false
        private val g = HashMap<Long, Double>()
        private val parent = HashMap<Long, Long>()
        private val open = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        private var reached: Long? = null
        private var route: Route? = null

        /** Work done so far, in the units a tick's budget is counted in. */
        var expanded = 0
            private set
        var finished = false
            private set
        /** How it ended: at a bank near enough, the best bank in all the water in reach, on toward
         *  water not yet searched, or no way at all. */
        var stoppedBy = ""
            private set

        init {
            cellSeen += startCell
            cellQueue += startCell
            depth[startCell] = 0
        }

        private fun key(x: Int, z: Int) = BlockPos.asLong(x, 0, z)
        private fun cellOf(x: Int, z: Int) = key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL))
        private fun cx(cell: Long) = BlockPos.getX(cell)
        private fun cz(cell: Long) = BlockPos.getZ(cell)
        /** Blocks walked the sail out to [cell] is worth. */
        private fun sail(cell: Long) = (depth[cell] ?: 0) * CELL / BOAT_PACE
        private fun cellH(cell: Long) = Math.hypot(cx(cell) * CELL + CELL / 2.0 - goal.x, cz(cell) * CELL + CELL / 2.0 - goal.z)
        private fun h(x: Int, z: Int) = Math.hypot(x + 0.5 - goal.x, z + 0.5 - goal.z)

        /** Searches on for up to [budget] units of work; true once the search is over. */
        fun step(budget: Int): Boolean {
            if (finished) return true
            var left = budget
            if (corridor == null) {
                while (left > 0) {
                    // Cells come out nearest the boat first: once sailing on to the next one costs
                    // more than the best trip found, nothing further out can beat it.
                    val nextSail = cellQueue.firstOrNull()?.let { (depth[it] ?: 0) * CELL / BOAT_PACE }
                    if (nextSail == null || nextSail >= bestCost || cellsVisited >= MAX_CELLS) {
                        if (!chooseTarget()) return finish("no way to a bank")
                        break
                    }
                    visitCell(cellQueue.removeFirst())
                    left -= CELL_COST
                    expanded += CELL_COST
                }
                if (corridor == null) return false
            }
            while (left > 0) {
                val t = target ?: return finish("no way to a bank")
                if (open.isEmpty() || g.size > MAX_COLUMNS) return finish("no way through")
                val (current, f) = open.poll()
                val cg = g[current] ?: continue
                val x = BlockPos.getX(current)
                val z = BlockPos.getZ(current)
                val tx = BlockPos.getX(t)
                val tz = BlockPos.getZ(t)
                if (f > cg + Math.hypot((x - tx).toDouble(), (z - tz).toDouble()) + 1e-6) continue // stale entry
                left--
                expanded++
                if (current == t) {
                    reached = current
                    return finish(if (onward) "on toward water not yet seen" else "best bank")
                }
                for (dx in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val nx = x + dx
                    val nz = z + dz
                    if (cellOf(nx, nz) !in corridor!!) continue
                    if (!grid.open(nx, nz)) continue
                    // No cutting a corner across the bank.
                    if (dx != 0 && dz != 0 && (!grid.open(x + dx, z) || !grid.open(x, z + dz))) continue
                    val step = (if (dx != 0 && dz != 0) DIAGONAL else 1.0) +
                        (if (grid.byBank(nx, nz)) BANK_PENALTY else if (!grid.wide(nx, nz)) NARROW_PENALTY else 0.0)
                    val ng = cg + step
                    val nk = key(nx, nz)
                    if (ng < (g[nk] ?: Double.MAX_VALUE)) {
                        g[nk] = ng
                        parent[nk] = current
                        open.add(nk to ng + Math.hypot((nx - tx).toDouble(), (nz - tz).toDouble()))
                    }
                }
            }
            return false
        }

        private fun visitCell(cell: Long) {
            cellsVisited++
            val x0 = cx(cell) * CELL
            val z0 = cz(cell) * CELL
            // By the bank: look for somewhere to land.
            var whole = true
            for (i in 0 until CELL) for (j in 0 until CELL) if (!grid.open(x0 + i, z0 + j)) whole = false
            if (!whole) {
                for (i in 0 until CELL) for (j in 0 until CELL) {
                    val x = x0 + i
                    val z = z0 + j
                    if (!grid.open(x, z) || !grid.byBank(x, z)) continue
                    val shore = grid.landingAt(x, z) ?: continue
                    val cost = sail(cell) + Math.hypot(shore.x - goal.x, shore.z - goal.z)
                    if (cost < bestCost) {
                        bestCost = cost
                        bestShore = shore
                        bestColumn = key(x, z)
                        bestCell = cell
                        bestIsEdge = false
                    }
                }
            }
            for ((dx, dz) in SIDES) {
                val next = key(cx(cell) + dx, cz(cell) + dz)
                if (next in cellSeen) continue
                val nx0 = cx(next) * CELL
                val nz0 = cz(next) * CELL
                if (Math.abs(nx0 - start.first) > MAX_RANGE || Math.abs(nz0 - start.second) > MAX_RANGE || !grid.known(nx0, nz0)) {
                    // The water runs on out of sight here: going on over it is a trip too.
                    val cost = sail(cell) + cellH(cell) * UNSEEN_WATER / BOAT_PACE
                    if (cost < bestCost) {
                        openColumnIn(cell)?.let { column ->
                            bestCost = cost
                            bestCell = cell
                            bestColumn = column
                            bestShore = null
                            bestIsEdge = true
                        }
                    }
                    continue
                }
                if (!linked(x0, z0, dx, dz)) continue
                cellSeen += next
                cellParent[next] = cell
                depth[next] = (depth[cell] ?: 0) + 1
                cellQueue += next
            }
        }

        /** Water the hull fits through runs across the side of the cell at ([x0], [z0]) facing ([dx], [dz]). */
        private fun linked(x0: Int, z0: Int, dx: Int, dz: Int): Boolean {
            for (k in 0 until CELL) {
                val (ax, az) = when {
                    dx > 0 -> (x0 + CELL - 1) to (z0 + k)
                    dx < 0 -> x0 to (z0 + k)
                    dz > 0 -> (x0 + k) to (z0 + CELL - 1)
                    else -> (x0 + k) to z0
                }
                if (grid.open(ax, az) && grid.open(ax + dx, az + dz)) return true
            }
            return false
        }

        /** After the first pass: where the way goes, and the cells it goes by. */
        private fun chooseTarget(): Boolean {
            val endCell = bestCell ?: return false
            val endColumn = bestColumn ?: return false
            onward = bestIsEdge
            // The cells on the way there, and their neighbours, for the column search to go through.
            val way = HashSet<Long>()
            var at: Long? = endCell
            while (at != null) {
                for (dx in -1..1) for (dz in -1..1) way += key(cx(at) + dx, cz(at) + dz)
                at = cellParent[at]
            }
            corridor = way
            target = endColumn
            val s = key(start.first, start.second)
            g[s] = 0.0
            open.add(s to Math.hypot((start.first - BlockPos.getX(endColumn)).toDouble(), (start.second - BlockPos.getZ(endColumn)).toDouble()))
            return true
        }

        private fun openColumnIn(cell: Long): Long? {
            for (i in 0 until CELL) for (j in 0 until CELL) {
                val x = cx(cell) * CELL + i
                val z = cz(cell) * CELL + j
                if (grid.open(x, z)) return key(x, z)
            }
            return null
        }

        private fun finish(why: String): Boolean {
            finished = true
            stoppedBy = why
            val end = reached ?: return true
            val columns = ArrayList<Long>()
            var at: Long? = end
            while (at != null) {
                columns += at
                at = parent[at]
            }
            columns.reverse()
            val y = grid.y + 0.5
            val points = simplify(grid, columns).map { Vec3(BlockPos.getX(it) + 0.5, y, BlockPos.getZ(it) + 0.5) }
            val landing = Vec3(BlockPos.getX(end) + 0.5, y, BlockPos.getZ(end) + 0.5)
            val shore = if (onward) Vec3(landing.x, grid.y + 1.0, landing.z) else bestShore ?: return true
            route = Route(points, landing, shore, g[end] ?: 0.0, complete = !onward)
            return true
        }

        /** The way found, once [finished]; null if there's none. */
        fun result(): Route? = route

        private companion object {
            val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        }
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
    internal class Grid(val level: ServerLevel, val y: Int, val clearance: Int) {
        private val water = HashMap<Long, Boolean>()
        private val fits = HashMap<Long, Boolean>()

        /** Whether the ground here is loaded or has been seen — see [WaterMap]. */
        fun known(x: Int, z: Int): Boolean = WaterMap.known(level, y, x, z)

        /** Open water at the surface with room above it, as seen now or last seen. */
        fun water(x: Int, z: Int): Boolean = water.getOrPut(BlockPos.asLong(x, 0, z)) {
            WaterMap.kind(level, y, x, z) == WaterMap.Kind.WATER
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

        /** Two blocks of water to spare either side of the hull as well. */
        fun wide(x: Int, z: Int): Boolean =
            open(x + 2, z) && open(x - 2, z) && open(x, z + 2) && open(x, z - 2) &&
                open(x + 2, z + 2) && open(x - 2, z - 2) && open(x + 2, z - 2) && open(x - 2, z + 2)

        /** Ground a man can step out onto beside this column, within the hull's reach and one more
         *  block — the bank the boat can come up against — or null. */
        fun landingAt(x: Int, z: Int): Vec3? {
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
