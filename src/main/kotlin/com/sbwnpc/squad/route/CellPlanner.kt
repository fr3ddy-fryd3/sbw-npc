package com.sbwnpc.squad.route

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue

/**
 * Long routes over the surface of the world — for a boat, a man on foot or a vehicle, whichever
 * [Medium] says what can be crossed — to wherever the trip is best ended for [Search.goal].
 *
 * The world is taken in [CELL]-block cells, and the cells searched best-first toward the goal
 * (A*), several hundred a tick ([Search.step]): a way round a mountain range or a bay two hundred
 * blocks across is found, not just the next hundred blocks of it. Then the way itself is found
 * column by column, only through the cells the first pass went by.
 *
 * How a trip ends is the [Medium]'s: a boat puts its crew on a bank and they walk in, a vehicle can
 * stop anywhere and its crew walk on, a man on foot walks right up to the goal. Every way to end
 * it costs the whole trip in blocks walked ([Medium.pace]), and the cheapest is taken.
 *
 * Only ground that is loaded or remembered is known. The rest is taken as crossable, at
 * [UNSEEN] times the cost — the usual assumption when planning across a world seen bit by bit: the
 * way goes on through it toward the goal, the traveller follows it as far as the known ground
 * goes ([Route.complete] false), the ground there loads, and it looks again. Deciding as if the
 * world ended where the loaded ground did set boat crews down hundreds of blocks short.
 */
object CellPlanner {
    /** Blocks along a side of a cell of the first pass. */
    const val CELL = 4
    /** Ground not yet seen costs this many times as much to cross — a guess, made good on arrival. */
    const val UNSEEN = 1.3
    /** Cells searched at most. */
    private const val MAX_CELLS = 80_000
    /** Columns of the second pass at most. */
    private const val MAX_COLUMNS = 60_000
    /** Nor farther than this from the start. */
    private const val MAX_RANGE = 768
    /** A cell of the first pass counts as this many units of a tick's budget. */
    private const val CELL_COST = 12
    /** Straight stretches of the route are kept as one leg up to this many columns. */
    private const val MAX_LEG = 24
    private val DIAGONAL = Math.sqrt(2.0)
    private val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** What a traveller can cross, how fast, and where a trip can end. */
    interface Medium {
        /** Blocks walked one block of travel is worth: a boat's 0.4, a man's 1. */
        val pace: Double
        /** Whether anything is known of the ground at this column. */
        fun known(x: Int, z: Int): Boolean
        /** The traveller fits with its middle over this column. */
        fun open(x: Int, z: Int): Boolean
        /** It can get from one open column to the next — a step not too high, say. */
        fun step(ax: Int, az: Int, bx: Int, bz: Int): Boolean
        /** Extra blocks of travel stepping onto this column costs: by a bank, in water... */
        fun extraCost(x: Int, z: Int): Double
        /** Where the traveller gets off if the trip ends at this column, or null if it can't. */
        fun exitAt(x: Int, z: Int, goal: Vec3): Vec3?
        /** Blocks walked from getting off at [exit] to the goal. */
        fun remaining(exit: Vec3, goal: Vec3): Double = Math.hypot(exit.x - goal.x, exit.z - goal.z)
        /** The height of a route point over this column. */
        fun pointY(x: Int, z: Int): Double
    }

    /**
     * [route] (its turns; [trail], every column) runs from the start to [landing], the column the trip ends at, where the traveller
     * gets off at [shore]. Not [complete] when it runs on into ground not yet seen: then [landing]
     * is as far as the known ground goes, and the traveller looks again from there.
     */
    class Route(
        val route: List<Vec3>,
        /** Every column of the way, a block or so apart — for walking it in a column of men. */
        val trail: List<Vec3>,
        val landing: Vec3,
        val shore: Vec3,
        val length: Double,
        val complete: Boolean,
        private val pace: Double,
    ) {
        /** What's left after this route, in blocks walked: the walk in, or the rest of the way
         *  over ground not yet seen. */
        fun remaining(goal: Vec3): Double {
            val d = Math.hypot(shore.x - goal.x, shore.z - goal.z)
            return if (complete) d else d * UNSEEN * pace
        }
    }

    fun search(medium: Medium, from: Vec3, goal: Vec3): Search? {
        val x = Math.floor(from.x).toInt()
        val z = Math.floor(from.z).toInt()
        for (r in 0..3) for (dx in -r..r) for (dz in -r..r) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue
            if (medium.known(x + dx, z + dz) && medium.open(x + dx, z + dz)) return Search(medium, x + dx, z + dz, goal)
        }
        return null
    }

    /** One search under way — see [step] and [result]. */
    class Search internal constructor(
        private val medium: Medium,
        private val startX: Int,
        private val startZ: Int,
        val goal: Vec3,
    ) {
        private val cellG = HashMap<Long, Double>()
        private val cellParent = HashMap<Long, Long>()
        private val cellOpen = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        private val cellKnown = HashMap<Long, Boolean>()
        private var cellsVisited = 0

        /** The cheapest way to end the trip so far: the cell and column, where to get off, and the
         *  whole trip's cost in blocks walked. [bestUnseen]: going on into ground not yet seen. */
        private var bestCost = Double.MAX_VALUE
        private var bestCell: Long? = null
        private var bestColumn: Long? = null
        private var bestExit: Vec3? = null
        private var bestUnseen = false

        private var corridor: HashSet<Long>? = null
        private var target: Long? = null
        private var complete = true
        private val g = HashMap<Long, Double>()
        private val parent = HashMap<Long, Long>()
        private val open = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        private var route: Route? = null

        /** Work done so far, in the units a tick's budget is counted in. */
        var expanded = 0
            private set
        var finished = false
            private set
        /** How it ended — see [finish]. */
        var stoppedBy = ""
            private set

        init {
            val c = key(Math.floorDiv(startX, CELL), Math.floorDiv(startZ, CELL))
            cellG[c] = 0.0
            cellOpen.add(c to cellH(c))
        }

        private fun key(x: Int, z: Int) = BlockPos.asLong(x, 0, z)
        private fun kx(k: Long) = BlockPos.getX(k)
        private fun kz(k: Long) = BlockPos.getZ(k)
        private fun cellOf(x: Int, z: Int) = key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL))
        private fun cellH(c: Long) = Math.hypot(kx(c) * CELL + CELL / 2.0 - goal.x, kz(c) * CELL + CELL / 2.0 - goal.z) * medium.pace
        private fun known(c: Long) = cellKnown.getOrPut(c) { medium.known(kx(c) * CELL, kz(c) * CELL) }
        private fun inRange(c: Long) =
            Math.abs(kx(c) * CELL - startX) <= MAX_RANGE && Math.abs(kz(c) * CELL - startZ) <= MAX_RANGE

        /** Searches on for up to [budget] units of work; true once the search is over. */
        fun step(budget: Int): Boolean {
            if (finished) return true
            var left = budget
            if (corridor == null) {
                while (left > 0) {
                    val next = cellOpen.peek()
                    if (next == null || next.second >= bestCost || cellsVisited >= MAX_CELLS) {
                        if (!chooseTarget()) return finish("no way")
                        break
                    }
                    cellOpen.poll()
                    val (cell, f) = next
                    val cg = cellG[cell] ?: continue
                    if (f > cg + cellH(cell) + 1e-9) continue // stale entry
                    visitCell(cell, cg)
                    left -= CELL_COST
                    expanded += CELL_COST
                }
                if (corridor == null) return false
            }
            val t = target ?: return finish("no way")
            val tx = kx(t)
            val tz = kz(t)
            while (left > 0) {
                if (open.isEmpty() || g.size > MAX_COLUMNS) return finish("no way through")
                val (current, f) = open.poll()
                val cg = g[current] ?: continue
                val x = kx(current)
                val z = kz(current)
                if (f > cg + Math.hypot((x - tx).toDouble(), (z - tz).toDouble()) + 1e-9) continue // stale entry
                left--
                expanded++
                if (current == t) return finish(if (complete) "to the end" else "on into ground not yet seen", current)
                for (dx in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val nx = x + dx
                    val nz = z + dz
                    if (cellOf(nx, nz) !in corridor!!) continue
                    if (!medium.open(nx, nz) || !medium.step(x, z, nx, nz)) continue
                    // No cutting a corner.
                    if (dx != 0 && dz != 0 && (!medium.open(x + dx, z) || !medium.open(x, z + dz))) continue
                    val ng = cg + (if (dx != 0 && dz != 0) DIAGONAL else 1.0) + medium.extraCost(nx, nz)
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

        private fun visitCell(cell: Long, cg: Double) {
            cellsVisited++
            if (known(cell)) {
                for (i in 0 until CELL) for (j in 0 until CELL) {
                    val x = kx(cell) * CELL + i
                    val z = kz(cell) * CELL + j
                    if (!medium.open(x, z)) continue
                    val exit = medium.exitAt(x, z, goal) ?: continue
                    val cost = cg + medium.remaining(exit, goal)
                    if (cost < bestCost) {
                        bestCost = cost
                        bestCell = cell
                        bestColumn = key(x, z)
                        bestExit = exit
                        bestUnseen = false
                    }
                }
            } else {
                // Nothing known here: going on over it toward the goal is a way to end it too.
                val cost = cg + cellH(cell) * UNSEEN
                if (cost < bestCost) {
                    bestCost = cost
                    bestCell = cell
                    bestColumn = null
                    bestExit = null
                    bestUnseen = true
                }
            }
            for ((dx, dz) in SIDES) {
                val next = key(kx(cell) + dx, kz(cell) + dz)
                if (!inRange(next)) continue
                if (!passable(cell, next, dx, dz)) continue
                val ng = cg + CELL * medium.pace * if (known(next)) 1.0 else UNSEEN
                if (ng < (cellG[next] ?: Double.MAX_VALUE)) {
                    cellG[next] = ng
                    cellParent[next] = cell
                    cellOpen.add(next to ng + cellH(next))
                }
            }
        }

        /** The traveller can cross from [cell] to its neighbour [next] facing ([dx], [dz]). */
        private fun passable(cell: Long, next: Long, dx: Int, dz: Int): Boolean {
            if (!known(next) || !known(cell)) return true
            val x0 = kx(cell) * CELL
            val z0 = kz(cell) * CELL
            for (k in 0 until CELL) {
                val (ax, az) = when {
                    dx > 0 -> (x0 + CELL - 1) to (z0 + k)
                    dx < 0 -> x0 to (z0 + k)
                    dz > 0 -> (x0 + k) to (z0 + CELL - 1)
                    else -> (x0 + k) to z0
                }
                if (medium.open(ax, az) && medium.open(ax + dx, az + dz) && medium.step(ax, az, ax + dx, az + dz)) return true
            }
            return false
        }

        /** After the first pass: where the way goes, and the cells it goes by. The way is followed
         *  only as far as the known ground: past that it's a guess. */
        private fun chooseTarget(): Boolean {
            var endCell = bestCell ?: return false
            val cells = ArrayList<Long>()
            var at: Long? = endCell
            while (at != null) {
                cells += at
                at = cellParent[at]
            }
            cells.reverse()
            val firstUnseen = cells.indexOfFirst { !known(it) }
            val endColumn: Long
            if (firstUnseen >= 0) {
                if (firstUnseen == 0) return false
                complete = false
                endCell = cells[firstUnseen - 1]
                endColumn = columnToward(endCell, cells[firstUnseen]) ?: return false
                cells.subList(firstUnseen, cells.size).clear()
            } else {
                complete = !bestUnseen
                endColumn = bestColumn ?: return false
            }
            val way = HashSet<Long>()
            for (c in cells) for (dx in -1..1) for (dz in -1..1) way += key(kx(c) + dx, kz(c) + dz)
            corridor = way
            target = endColumn
            val s = key(startX, startZ)
            g[s] = 0.0
            open.add(s to Math.hypot((startX - kx(endColumn)).toDouble(), (startZ - kz(endColumn)).toDouble()))
            return true
        }

        /** An open column of [cell] on its side facing [toward]. */
        private fun columnToward(cell: Long, toward: Long): Long? {
            val cx = (kx(toward) * CELL + CELL / 2.0)
            val cz = (kz(toward) * CELL + CELL / 2.0)
            var best: Long? = null
            var bestD = Double.MAX_VALUE
            for (i in 0 until CELL) for (j in 0 until CELL) {
                val x = kx(cell) * CELL + i
                val z = kz(cell) * CELL + j
                if (!medium.open(x, z)) continue
                val d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz)
                if (d < bestD) {
                    bestD = d
                    best = key(x, z)
                }
            }
            return best
        }

        private fun finish(why: String, end: Long? = null): Boolean {
            finished = true
            stoppedBy = why
            end ?: return true
            val columns = ArrayList<Long>()
            var at: Long? = end
            while (at != null) {
                columns += at
                at = parent[at]
            }
            columns.reverse()
            val point = { c: Long -> Vec3(kx(c) + 0.5, medium.pointY(kx(c), kz(c)), kz(c) + 0.5) }
            val points = simplify(columns).map(point)
            val landing = Vec3(kx(end) + 0.5, medium.pointY(kx(end), kz(end)), kz(end) + 0.5)
            val shore = if (complete) bestExit ?: landing else landing
            route = Route(points, columns.map(point), landing, shore, g[end] ?: 0.0, complete, medium.pace)
            return true
        }

        /** Keeps the turns of the route and drops the columns between them wherever it runs
         *  straight — up to [MAX_LEG] at a time, so a leg never runs far past a bend. */
        private fun simplify(columns: List<Long>): List<Long> {
            if (columns.size <= 2) return columns
            val out = ArrayList<Long>()
            var i = 0
            out += columns[0]
            while (i < columns.size - 1) {
                var j = minOf(columns.size - 1, i + MAX_LEG)
                while (j > i + 1 && !straight(columns[i], columns[j])) j--
                out += columns[j]
                i = j
            }
            return out
        }

        /** A straight run between two columns is open, and steppable, all the way. */
        private fun straight(a: Long, b: Long): Boolean {
            val ax = kx(a) + 0.5
            val az = kz(a) + 0.5
            val bx = kx(b) + 0.5
            val bz = kz(b) + 0.5
            val steps = Math.ceil(Math.hypot(bx - ax, bz - az) * 2).toInt().coerceAtLeast(1)
            var px = kx(a)
            var pz = kz(a)
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val x = Math.floor(ax + (bx - ax) * t).toInt()
                val z = Math.floor(az + (bz - az) * t).toInt()
                if (x == px && z == pz) continue
                if (!medium.open(x, z) || !medium.step(px, pz, x, z)) return false
                px = x
                pz = z
            }
            return true
        }

        /** The way found, once [finished]; null if there's none. */
        fun result(): Route? = route
    }
}
