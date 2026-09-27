package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.pathfinder.PathFinder
import java.util.UUID

/**
 * One route for a whole squad on a long march, planned by one of its men and walked by all.
 *
 * Each man planning his own way with the ordinary search found no way round anything big: when A*
 * runs out of nodes it hands back the path to whichever node is nearest the goal in a straight
 * line, and with a hill in the way that is the foot of the hill — the squad stood pressed into the
 * slope. Finding the way round takes a search many times the size, too dear to run for every man.
 *
 * So one man searches [LEG] blocks toward the goal with a budget of [NODES] nodes, and the route
 * he finds becomes the squad's: everyone aims a little way ahead of their own place along it
 * with the ordinary short search, and when the front reaches its end the next leg is planned from
 * there. Only one such search runs per server tick, so squads ordered off together don't stall
 * the server between them. A leg that got nowhere is tried again turned off to one side, then the
 * other, wider each time.
 */
object SquadMarch {
    /** How far toward the goal each leg aims. */
    private const val LEG = 120.0
    /** How far a leg's path may wander to get there. */
    private const val SEARCH_RANGE = 200f
    private const val NODES = 20_000
    /** Nodes ahead of a man's own place on the route that he walks toward. */
    private const val LOOKAHEAD = 16
    /** Within this many nodes of the end, the front has arrived and the next leg is due. */
    private const val END_NODES = 8
    /** A requested goal this near the route's own is the same march (formation slots differ). */
    private const val SAME_GOAL = 40.0
    private const val MIN_REPLAN_TICKS = 40L
    /** A leg ending nearer than this to where it started made no headway. */
    private const val NO_PROGRESS = 8.0
    private const val TURN_DEGREES = 50.0
    /** Nodes back along the route that the formation's heading is taken over. */
    private const val HEADING_NODES = 4
    /** A formation place this far above or below the route is off the path the route found. */
    private const val MAX_STEP_FROM_ROUTE = 4.0

    /** A man this near a route's nodes (or where an unplanned one starts) is on it. */
    private const val JOIN = 12.0
    /** Routes a squad may have at once — parties bound elsewhere, stragglers finding their way. */
    private const val MAX_MARCHES = 8
    /** A route nobody has asked for in this long is dropped. */
    private const val IDLE_TICKS = 200L
    /** Older nodes beyond this many are dropped from the back of a growing route. */
    private const val MAX_ROUTE_NODES = 400

    private class March(val goal: BlockPos, val stamp: Int, val origin: BlockPos) {
        var route: List<BlockPos> = emptyList()
        var lastUsed = 0L
        // Far enough back that a first leg is due at once, near enough that "now - plannedAt"
        // can't overflow (from Long.MIN_VALUE it came out negative and no leg was ever planned).
        var plannedAt = -MIN_REPLAN_TICKS
        var failures = 0
        /** Legs that got nowhere, all told — past [GIVE_UP] the goal has no way to it on foot. */
        var misses = 0
        /** The route's last node is the goal's own column — no further legs needed. */
        var complete = false
    }

    private val bySquad = HashMap<UUID, MutableList<March>>()
    private var lastPlanTick = Long.MIN_VALUE

    fun clearAll() {
        bySquad.clear()
        lastPlanTick = Long.MIN_VALUE
    }

    /** Where to walk next: [formation] is the man's own place in the squad's formation round the
     *  route, null where the ground there won't take him; [route] is the route itself. */
    class Waypoint(val route: BlockPos, val formation: BlockPos?)

    /**
     * Where [npc] should walk next on its squad's way to [goal], or null when there is no squad
     * route to follow (no squad, or not planned yet — the caller falls back to its own leg).
     */
    fun waypointFor(npc: NpcEntity, goal: BlockPos): Waypoint? {
        val level = npc.level() as? ServerLevel ?: return null
        val squad = npc.currentSquad() ?: return null
        val marches = bySquad.getOrPut(squad.id) { ArrayList() }
        marches.removeAll { it.stamp != squad.orderStamp || level.gameTime - it.lastUsed > IDLE_TICKS }
        // The route this man is on: bound for the same place and passing close by him. One route
        // per squad had two parties bound for different points re-planning it from under each
        // other every second, and left a man who had strayed off it — down a cave — aiming for a
        // node sixty blocks away through rock. Anyone on no route gets one of his own.
        val march = marches
            .filter { it.goal.closerThan(goal, SAME_GOAL) }
            .map { it to distanceTo(it, npc) }
            .filter { it.second <= JOIN }
            .minByOrNull { it.second }?.first
            ?: (if (marches.size < MAX_MARCHES) March(goal, squad.orderStamp, npc.blockPosition()).also { marches += it } else return null)
        march.lastUsed = level.gameTime
        val route = march.route
        val here = nearestIndex(route, npc)
        val atEnd = route.isEmpty() || here >= route.size - END_NODES
        if (atEnd && !march.complete && level.gameTime - march.plannedAt >= MIN_REPLAN_TICKS && lastPlanTick != level.gameTime) {
            plan(level, npc, march, squad.name)
            val planned = march.route
            if (planned.isEmpty()) return null
            val at = nearestIndex(planned, npc)
            return Waypoint(planned[(at + LOOKAHEAD).coerceAtMost(planned.size - 1)], formationSpot(level, npc, march, at))
        }
        if (route.isEmpty()) return null
        return Waypoint(route[(here + LOOKAHEAD).coerceAtMost(route.size - 1)], formationSpot(level, npc, march, here))
    }

    /**
     * [npc]'s place in its squad's marching formation — a wedge on the attack — laid round the
     * route rather than on it: the formation's point is [LOOKAHEAD] nodes ahead of the squad's lead
     * man, turned along the way the route runs there. Walking the route itself had the whole squad
     * strung out one behind the other. Null where that place is no good — no ground to stand on
     * near the route's height — and the man keeps to the route there, as he would through a gap.
     */
    private fun formationSpot(level: ServerLevel, npc: NpcEntity, march: March, here: Int): BlockPos? {
        val squad = npc.currentSquad() ?: return null
        val route = march.route
        if (route.size < 2) return null
        val slot = npc.slotIndex(squad)
        if (slot < 0) return null
        val local = com.sbwnpc.squad.combat.SquadFormation.transitOffset(squad.order, slot, squad.members.size)
        // Everyone measures from the same man, or each would put the formation's point sixteen
        // nodes ahead of himself and the rear would never close up.
        val lead = (level.getEntity(squad.members.first()) as? NpcEntity)
            ?.takeIf { it.isAlive && distanceTo(march, it) <= JOIN * 2 }
        val leadAt = lead?.let { nearestIndex(route, it) } ?: here
        val a = (leadAt + LOOKAHEAD).coerceAtMost(route.size - 1)
        val anchor = route[a]
        val back = route[(a - HEADING_NODES).coerceAtLeast(0)]
        val dx = (anchor.x - back.x).toDouble()
        val dz = (anchor.z - back.z).toDouble()
        val len = Math.sqrt(dx * dx + dz * dz)
        if (len < 1.0) return null
        val fx = dx / len
        val fz = dz / len
        val x = anchor.x + 0.5 + fx * local.z - fz * local.x
        val z = anchor.z + 0.5 + fz * local.z + fx * local.x
        val ground = com.sbwnpc.squad.util.Terrain.standableOrNull(level, x, anchor.y + 1.0, z) ?: return null
        if (Math.abs(ground.y - anchor.y) > MAX_STEP_FROM_ROUTE) return null
        return BlockPos.containing(ground)
    }

    /** How far [npc] is from [march]'s route — or, before it has one, from where it starts. */
    private fun distanceTo(march: March, npc: NpcEntity): Double {
        if (march.route.isEmpty()) return Math.sqrt(march.origin.distToCenterSqr(npc.x, npc.y, npc.z))
        return Math.sqrt(march.route[nearestIndex(march.route, npc)].distToCenterSqr(npc.x, npc.y, npc.z))
    }

    private fun nearestIndex(route: List<BlockPos>, npc: NpcEntity): Int {
        var best = 0
        var bestSqr = Double.MAX_VALUE
        for (i in route.indices) {
            val d = route[i].distToCenterSqr(npc.x, npc.y, npc.z)
            if (d <= bestSqr) {
                best = i
                bestSqr = d
            }
        }
        return best
    }

    private fun plan(level: ServerLevel, npc: NpcEntity, march: March, squadName: String) {
        lastPlanTick = level.gameTime
        march.plannedAt = level.gameTime
        val started = System.nanoTime()
        val dx = march.goal.x + 0.5 - npc.x
        val dz = march.goal.z + 0.5 - npc.z
        val len = Math.sqrt(dx * dx + dz * dz)
        if (len < 1.0) return
        // Turned off the straight line after legs that went nowhere: +50, -50, +100, -100...
        val turns = (march.failures + 1) / 2
        val sign = if (march.failures % 2 == 1) 1.0 else -1.0
        val angle = Math.toRadians(sign * turns * TURN_DEGREES)
        val ux = (dx / len) * Math.cos(angle) - (dz / len) * Math.sin(angle)
        val uz = (dx / len) * Math.sin(angle) + (dz / len) * Math.cos(angle)
        // A turned leg still goes a fair way out, or a goal thirty blocks off would only ever be
        // tried from thirty blocks round the same hillside.
        val straight = march.failures == 0
        val reach = if (straight) minOf(len, LEG) else minOf(LEG, maxOf(len, TURNED_LEG_MIN))
        val target = surfaceToward(level, npc, ux, uz, reach) ?: return

        val from = npc.blockPosition()
        val r = SEARCH_RANGE.toInt() + 8
        val region = PathNavigationRegion(level, from.offset(-r, -r, -r), from.offset(r, r, r))
        val evaluator = VehicleAwareNodeEvaluator().apply { setCanPassDoors(true) }
        val path = PathFinder(evaluator, NODES).findPath(region, npc, setOf(target), SEARCH_RANGE, 1, 1f)
        val route = path?.let { p -> (0 until p.nodeCount).map { p.getNode(it).asBlockPos() } }.orEmpty()
        val end = route.lastOrNull()
        val progressed = end != null && Math.sqrt(end.distSqr(from)) >= NO_PROGRESS
        val reached = path?.canReach() == true
        if (progressed) {
            // Added on, not swapped in: the men still behind on the last leg keep their way.
            march.route = (march.route + route).takeLast(MAX_ROUTE_NODES)
            march.failures = 0
            // Done once a straight leg found its way right to the goal; from there the ordinary
            // search takes each man to his own place.
            march.complete = straight && reached && len <= LEG
        } else {
            march.failures++
            march.misses++
        }
        DebugFlags.log(
            "[march-debug] {} route {} leg from {} toward {} (goal {}, {} blocks, turn {}): {} nodes, reached={}, end={}, {} ms",
            squadName, march.origin, from, target, march.goal, len.toInt(), if (straight) 0 else march.failures,
            route.size, reached, end, "%.1f".format((System.nanoTime() - started) / 1.0e6)
        )
        if (march.misses >= GIVE_UP) {
            march.complete = true
            DebugFlags.log("[march-debug] {} found no way on foot to {}, holding where it got to", squadName, march.goal)
        }
    }

    private const val TURNED_LEG_MIN = 48.0
    private const val GIVE_UP = 6

    /** Close enough to the goal that the ordinary search takes the squad the rest of the way. */
    private const val GOAL_REACH = 24.0

    /** The farthest point on loaded ground up to [reach] along (ux, uz), on the surface. */
    private fun surfaceToward(level: ServerLevel, npc: NpcEntity, ux: Double, uz: Double, reach: Double): BlockPos? {
        var d = reach
        while (d >= 8.0) {
            val x = Math.floor(npc.x + ux * d).toInt()
            val z = Math.floor(npc.z + uz * d).toInt()
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4)
            if (chunk != null) {
                return BlockPos(x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1, z)
            }
            d -= 8.0
        }
        return null
    }
}
