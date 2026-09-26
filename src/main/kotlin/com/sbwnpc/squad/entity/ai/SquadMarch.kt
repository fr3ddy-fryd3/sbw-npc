package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
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

    private class March(val goal: BlockPos, val stamp: Int) {
        var route: List<BlockPos> = emptyList()
        var plannedAt = Long.MIN_VALUE
        var failures = 0
        /** Legs that got nowhere, all told — past [GIVE_UP] the goal has no way to it on foot. */
        var misses = 0
        /** The route's last node is the goal's own column — no further legs needed. */
        var complete = false
    }

    private val bySquad = HashMap<UUID, March>()
    private var lastPlanTick = Long.MIN_VALUE

    fun clearAll() {
        bySquad.clear()
        lastPlanTick = Long.MIN_VALUE
    }

    /**
     * Where [npc] should walk next on its squad's way to [goal], or null when there is no squad
     * route to follow (no squad, or not planned yet — the caller falls back to its own leg).
     */
    fun waypointFor(npc: NpcEntity, goal: BlockPos): BlockPos? {
        val level = npc.level() as? ServerLevel ?: return null
        val squad = npc.currentSquad() ?: return null
        var march = bySquad[squad.id]
        if (march == null || march.stamp != squad.orderStamp ||
            !march.goal.closerThan(goal, SAME_GOAL)
        ) {
            march = March(goal, squad.orderStamp)
            bySquad[squad.id] = march
        }
        val route = march.route
        val here = nearestIndex(route, npc)
        val atEnd = route.isEmpty() || here >= route.size - END_NODES
        if (atEnd && !march.complete && level.gameTime - march.plannedAt >= MIN_REPLAN_TICKS && lastPlanTick != level.gameTime) {
            plan(level, npc, march, squad.name)
            return march.route.getOrNull((nearestIndex(march.route, npc) + LOOKAHEAD).coerceAtMost(march.route.size - 1))
        }
        if (route.isEmpty()) return null
        return route[(here + LOOKAHEAD).coerceAtMost(route.size - 1)]
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
        val evaluator = WalkNodeEvaluator().apply { setCanPassDoors(true) }
        val path = PathFinder(evaluator, NODES).findPath(region, npc, setOf(target), SEARCH_RANGE, 1, 1f)
        val route = path?.let { p -> (0 until p.nodeCount).map { p.getNode(it).asBlockPos() } }.orEmpty()
        val end = route.lastOrNull()
        val progressed = end != null && Math.sqrt(end.distSqr(from)) >= NO_PROGRESS
        val reached = path?.canReach() == true
        if (progressed) {
            march.route = route
            march.failures = 0
            // Done once a straight leg found its way right to the goal; from there the ordinary
            // search takes each man to his own place.
            march.complete = straight && reached && len <= LEG
        } else {
            march.failures++
            march.misses++
        }
        DebugFlags.log(
            "[march-debug] {} leg from {} toward {} (goal {}, {} blocks, turn {}): {} nodes, reached={}, end={}, {} ms",
            squadName, from, target, march.goal, len.toInt(), if (straight) 0 else march.failures,
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
