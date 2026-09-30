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
 * climbs out wherever. This searches the water surface the boat floats on, wide enough everywhere
 * for the hull. The goal is usually on land, so the search ends at the place a boat can put its
 * crew ashore — water next to ground a man can stand on — nearest the goal.
 *
 * Two passes. First the water is taken in [CELL]-block cells, and every cell the boat can reach is
 * visited outward from it, nearest first ([Search] runs a few hundred a tick): the bank nearest the
 * goal is the best one anywhere in reach, however far round a bay the way to it goes. A search
 * drawn straight at the goal, as this used to be, spent everything it had on a bay pointing at the
 * goal that led nowhere and set the crew down there. Then the way itself is found column by column,
 * only through the cells the first pass went by, keeping to the middle of the water where it can —
 * a boat at speed needs fifty blocks to turn round.
 *
 * Only loaded ground is searched; water running on into ground that isn't loaded, or past
 * [MAX_RANGE], nearer the goal than any bank found makes the voyage go on: the boat sails there
 * and looks again.
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
    /** A landing this near the goal is as good as any: the search stops there. */
    private const val CLOSE_ENOUGH = 12.0
    /** Water left unsearched this much nearer the goal than the best bank means the voyage goes on. */
    private const val FRONTIER_GAIN = 16.0
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
    class Route(val route: List<Vec3>, val landing: Vec3, val shore: Vec3, val length: Double, val complete: Boolean)

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
        /** The best bank so far: its water column, where a man steps out, and how far that is from the goal. */
        private var landingColumn: Long? = null
        private var landingCell: Long? = null
        private var bestShore: Vec3? = null
        private var bestScore = Double.MAX_VALUE
        /** The cell the search reached an edge at — its range or unloaded ground — nearest the goal. */
        private var edgeCell: Long? = null
        private var edgeH = Double.MAX_VALUE
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
        }

        private fun key(x: Int, z: Int) = BlockPos.asLong(x, 0, z)
        private fun cellOf(x: Int, z: Int) = key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL))
        private fun cx(cell: Long) = BlockPos.getX(cell)
        private fun cz(cell: Long) = BlockPos.getZ(cell)
        private fun cellH(cell: Long) = Math.hypot(cx(cell) * CELL + CELL / 2.0 - goal.x, cz(cell) * CELL + CELL / 2.0 - goal.z)
        private fun h(x: Int, z: Int) = Math.hypot(x + 0.5 - goal.x, z + 0.5 - goal.z)

        /** Searches on for up to [budget] units of work; true once the search is over. */
        fun step(budget: Int): Boolean {
            if (finished) return true
            var left = budget
            if (corridor == null) {
                while (left > 0) {
                    if (cellQueue.isEmpty() || cellsVisited >= MAX_CELLS || bestScore <= CLOSE_ENOUGH) {
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
                    return finish(
                        when {
                            onward -> "on toward water not yet searched"
                            bestScore <= CLOSE_ENOUGH -> "close enough"
                            else -> "best bank in reach"
                        }
                    )
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
                    val score = Math.hypot(shore.x - goal.x, shore.z - goal.z)
                    if (score < bestScore) {
                        bestScore = score
                        bestShore = shore
                        landingColumn = key(x, z)
                        landingCell = cell
                    }
                }
            }
            for ((dx, dz) in SIDES) {
                val next = key(cx(cell) + dx, cz(cell) + dz)
                if (next in cellSeen) continue
                val nx0 = cx(next) * CELL
                val nz0 = cz(next) * CELL
                if (Math.abs(nx0 - start.first) > MAX_RANGE || Math.abs(nz0 - start.second) > MAX_RANGE || !grid.loaded(nx0, nz0)) {
                    val hh = cellH(cell)
                    if (hh < edgeH) {
                        edgeH = hh
                        edgeCell = cell
                    }
                    continue
                }
                if (!linked(x0, z0, dx, dz)) continue
                cellSeen += next
                cellParent[next] = cell
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
            // Water still unsearched — the queue, and the edges — nearer the goal than the best bank.
            var frontier = edgeCell
            var frontierH = edgeH
            for (cell in cellQueue) {
                val hh = cellH(cell)
                if (hh < frontierH) {
                    frontierH = hh
                    frontier = cell
                }
            }
            val goOn = frontier?.takeIf { frontierH < bestScore - FRONTIER_GAIN }
            val endCell: Long
            val endColumn: Long
            if (goOn != null) {
                endCell = goOn
                endColumn = openColumnIn(goOn) ?: return false
                onward = true
            } else {
                endCell = landingCell ?: return false
                endColumn = landingColumn ?: return false
            }
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
        private val cursor = BlockPos.MutableBlockPos()

        fun loaded(x: Int, z: Int): Boolean = level.chunkSource.getChunkNow(x shr 4, z shr 4) != null

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
