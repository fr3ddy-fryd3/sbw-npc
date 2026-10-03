package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.route.CellPlanner
import com.sbwnpc.squad.route.PlanBudget
import com.sbwnpc.squad.route.Walking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * One route for a whole squad on a long march, planned by one of its men and walked by all.
 *
 * Each man planning his own way with the ordinary search found no way round anything big: when A*
 * runs out of nodes it hands back the path to whichever node is nearest the goal in a straight
 * line, and with a hill in the way that is the foot of the hill — the squad stood pressed into the
 * slope. Finding the way round takes a search many times the size, too dear to run for every man.
 *
 * The route comes from the long-route planner ([CellPlanner] on foot, [Walking]): the whole way
 * to the goal over the lie of the land as far as it's known, round hills and lakes, looked at
 * again as the squad nears the end of what it could see. Where that finds nothing — the squad is
 * inside a building or down a cave — it falls back on legs:
 *
 * one man searches [LEG] blocks toward the goal with a budget of [NODES] nodes, and the route
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
    /**
     * A leg ending this near its target (blocks, counted along each axis) got there. The target is
     * the top of the column — often the surface of a river or the top of a trunk, where nobody can
     * stand — and a search held to the exact block went through all [NODES] before giving up a
     * block or two short: 60–180 ms a time, half the legs planned in a forest march.
     */
    private const val LEG_REACH = 8
    /** Nodes ahead of a man's own place on the route that he walks toward. */
    private const val LOOKAHEAD = 16
    /** Within this many nodes of the end, the front has arrived and the next leg is due. */
    private const val END_NODES = 8
    /** The same for a long route that ran on into ground not yet seen: looked at again early,
     *  so the next stretch is there before the front runs out of the last. */
    private const val LONG_END_NODES = 32
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

    /** A man this near a route's nodes is on it — the width of a squad's formation round it. */
    private const val JOIN = 24.0
    /** Routes a squad may have at once — parties bound elsewhere, stragglers finding their way. */
    private const val MAX_MARCHES = 8
    /** A route nobody has asked for in this long is dropped. */
    private const val IDLE_TICKS = 1200L
    /** Older nodes beyond this many are dropped from the back of a growing route. */
    private const val MAX_ROUTE_NODES = 2000
    /** A way found from near here to the same goal this recently is taken again, not searched for. */
    private const val REUSE_TICKS = 1200L
    private const val MAX_REMEMBERED = 64
    /** Water this many route nodes long is worth a boat — see [crossingAhead]. */
    private const val MIN_CROSSING = 12
    /** How far ahead along the route a crossing is looked for. */
    private const val CROSSING_LOOKAHEAD = 48

    private class March(val goal: BlockPos, val stamp: Int, val origin: BlockPos) {
        val progress = MarchProgress(MAX_ROUTE_NODES)
        val route get() = progress.route
        var lastUsed = 0L
        // Far enough back that a first leg is due at once, near enough that "now - plannedAt"
        // can't overflow (from Long.MIN_VALUE it came out negative and no leg was ever planned).
        var plannedAt = -MIN_REPLAN_TICKS
        var failures = 0
        /** Legs that got nowhere, all told — past [GIVE_UP] the goal has no way to it on foot. */
        var misses = 0
        /** The route's last node is the goal's own column — no further legs needed. */
        var complete = false
        /** The long-route search under way for the next stretch, and when it began. */
        var search: CellPlanner.Search? = null
        var searchStarted = 0L
        /** The long-route planner found no way from here: planned in legs instead. */
        var legsOnly = false
        /** The route came from the long-route planner: looked at again further from its end. */
        var long = false
    }

    /** Squad id, or NPC id for an individual resupply trip. */
    private val byGroup = HashMap<UUID, MutableList<March>>()

    /** A way the long-route planner found lately — see [REUSE_TICKS]. */
    private class Found(val dimension: String, val from: BlockPos, val goal: BlockPos, val trail: List<BlockPos>, val complete: Boolean, val at: Long)
    private val found = ArrayList<Found>()
    private var lastPlanTick = Long.MIN_VALUE

    fun clearAll() {
        byGroup.clear()
        found.clear()
        lastPlanTick = Long.MIN_VALUE
    }

    /** Individual routes need no saved group to own them; discard them when the NPC leaves. */
    fun forgetIndividual(npc: UUID) {
        byGroup.remove(npc)
    }

    /** Where to walk next: [formation] is the man's own place in the squad's formation round the
     *  route, null where the ground there won't take him; [route] is the route itself. */
    class Waypoint(val route: BlockPos, val formation: BlockPos?)

    /**
     * Where [npc] should walk next on its squad's way to [goal], or null when there is no squad
     * route to follow (not planned yet — the caller falls back to its own leg). With formation
     * disabled, a lone NPC can use the same planner for a long resupply trip.
     */
    fun waypointFor(npc: NpcEntity, goal: BlockPos, followFormation: Boolean = true): Waypoint? {
        val level = npc.level() as? ServerLevel ?: return null
        val squad = npc.currentSquad()
        if (squad == null && followFormation) return null
        val stamp = squad?.orderStamp ?: 0
        val name = squad?.name ?: npc.uuid.toString().take(8)
        val marches = byGroup.getOrPut(squad?.id ?: npc.uuid) { ArrayList() }
        marches.removeAll { it.stamp != stamp || level.gameTime - it.lastUsed > IDLE_TICKS }
        // The route this man is on: bound for the same place and passing close by him. One route
        // per squad had two parties bound for different points re-planning it from under each
        // other every second, and left a man who had strayed off it — down a cave — aiming for a
        // node sixty blocks away through rock. Anyone on no route gets one of his own.
        // A route not planned yet is everyone's bound there: the whole squad waits on the one
        // search. By distance from whoever asked first, the far side of the formation started
        // searches of its own, and each of them stood still until its own came back.
        val bound = marches.filter { it.goal.closerThan(goal, SAME_GOAL) }
        val march = bound.firstOrNull { it.progress.contains(npc.uuid) && distanceTo(it, npc) <= JOIN * 2 }
            ?: bound
            .map { it to distanceTo(it, npc) }
            .filter { it.first.route.isNotEmpty() && it.second <= JOIN }
            .minByOrNull { it.second }?.first
            ?: bound.firstOrNull { it.route.isEmpty() }
            ?: (if (marches.size < MAX_MARCHES) March(goal, stamp, npc.blockPosition()).also { marches += it } else return null)
        march.lastUsed = level.gameTime
        val route = march.route
        val here = march.progress.index(npc.uuid, npc.position())
        val atEnd = route.isEmpty() || here >= route.size - if (march.long && !march.legsOnly) LONG_END_NODES else END_NODES
        if (atEnd && !march.complete && !march.legsOnly) {
            planLong(level, npc, march, goal, name)
            val planned = march.route
            if (planned.isEmpty()) return if (march.search != null) Waypoint(npc.blockPosition(), null) else null
            val at = march.progress.index(npc.uuid, npc.position())
            // A surface route can end short of the goal. Walk its useful stretch first, then
            // let a local 3D search find the way on; don't discard that stretch on the first call.
            if (!march.legsOnly || at < planned.size - END_NODES) {
                return Waypoint(planned[(at + LOOKAHEAD).coerceAtMost(planned.size - 1)],
                    if (followFormation) formationSpot(level, npc, march, at) else null)
            }
        }
        if (atEnd && !march.complete && level.gameTime - march.plannedAt >= MIN_REPLAN_TICKS && lastPlanTick != level.gameTime) {
            plan(level, npc, march, name)
            val planned = march.route
            if (planned.isEmpty()) return null
            val at = march.progress.index(npc.uuid, npc.position())
            return Waypoint(planned[(at + LOOKAHEAD).coerceAtMost(planned.size - 1)],
                if (followFormation) formationSpot(level, npc, march, at) else null)
        }
        if (route.isEmpty()) return null
        return Waypoint(route[(here + LOOKAHEAD).coerceAtMost(route.size - 1)],
            if (followFormation) formationSpot(level, npc, march, here) else null)
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
        val leadAt = lead?.let { march.progress.index(it.uuid, it.position()) } ?: here
        // The rear of a large wedge can be 48 blocks behind its anchor. Sixteen nodes ahead
        // minus that depth put its slot behind the walker, which then stood there indefinitely.
        val a = formationAnchorIndex(route, leadAt, here, local.z)
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
        // Near the end of a partial route the anchor cannot move far enough for a deep rear
        // slot. Follow the route itself there, or the rear never reaches the continuation trigger.
        if (!formationAdvances(route, here, ground)) return null
        return BlockPos.containing(ground)
    }

    /** Keep the formation's rear looking ahead of its own progress, even when the lead stops. */
    internal fun formationAnchorIndex(route: List<BlockPos>, leadAt: Int, here: Int, depth: Double): Int {
        var ahead = (here + LOOKAHEAD).coerceAtMost(route.lastIndex)
        var remaining = (-depth).coerceAtLeast(0.0)
        while (remaining > 0.0 && ahead < route.lastIndex) {
            remaining -= Math.hypot((route[ahead + 1].x - route[ahead].x).toDouble(),
                (route[ahead + 1].z - route[ahead].z).toDouble())
            ahead++
        }
        return maxOf(leadAt + LOOKAHEAD, ahead).coerceAtMost(route.lastIndex)
    }

    internal fun formationAdvances(route: List<BlockPos>, here: Int, spot: Vec3): Boolean {
        val at = route[here]
        val next = route[(here + LOOKAHEAD).coerceAtMost(route.lastIndex)]
        val dx = (next.x - at.x).toDouble()
        val dz = (next.z - at.z).toDouble()
        val length = Math.hypot(dx, dz)
        return length > 1.0e-3 && ((spot.x - at.x - 0.5) * dx + (spot.z - at.z - 0.5) * dz) / length > 1.5
    }

    /** A stretch of water on a squad's route: the last dry node before it and the first one after. */
    class Crossing(val shore: BlockPos, val farShore: BlockPos)

    /**
     * Water ahead of [npc] on its squad's route, at least [MIN_CROSSING] nodes of it and starting
     * within [CROSSING_LOOKAHEAD] nodes — worth a boat rather than a swim. Null when there is none,
     * or no route yet. A crossing that runs off the end of the route so far ends at its last node.
     */
    fun crossingAhead(npc: NpcEntity): Crossing? {
        val level = npc.level() as? ServerLevel ?: return null
        val squad = npc.currentSquad() ?: return null
        val marches = byGroup[squad.id]?.filter { it.stamp == squad.orderStamp && it.route.isNotEmpty() } ?: return null
        val march = marches.firstOrNull { it.progress.contains(npc.uuid) && distanceTo(it, npc) <= JOIN * 2 }
            ?: marches.map { it to distanceTo(it, npc) }.filter { it.second <= JOIN }.minByOrNull { it.second }?.first
            ?: return null
        val route = march.route
        val from = march.progress.index(npc.uuid, npc.position())
        var start = -1
        for (i in from until route.size) {
            val wet = level.getFluidState(route[i]).`is`(net.minecraft.tags.FluidTags.WATER)
            if (wet && start < 0) {
                if (i - from > CROSSING_LOOKAHEAD) return null
                start = i
            } else if (!wet && start >= 0) {
                if (i - start >= MIN_CROSSING) return Crossing(route[(start - 1).coerceAtLeast(0)], route[i])
                start = -1
            }
        }
        if (start >= 0 && route.size - start >= MIN_CROSSING) return Crossing(route[(start - 1).coerceAtLeast(0)], route.last())
        return null
    }

    /** How far [npc] is from [march]'s route — or, before it has one, from where it starts. */
    private fun distanceTo(march: March, npc: NpcEntity): Double {
        if (march.route.isEmpty()) return Math.sqrt(march.origin.distToCenterSqr(npc.x, npc.y, npc.z))
        return Math.sqrt(march.route[march.progress.index(npc.uuid, npc.position(), remember = false)].distToCenterSqr(npc.x, npc.y, npc.z))
    }

    /**
     * The way on from [npc] to [goal] by the long-route planner — over the whole lie of the land
     * as far as it's known, round the hills and lakes a hundred-block leg ran into blind. Runs over
     * a few ticks; the route it finds is walked on from where the last one ended. Where it finds
     * no way at all (inside a building, down a cave) the march goes back to legs.
     */
    private fun planLong(level: ServerLevel, npc: NpcEntity, march: March, goal: BlockPos, squadName: String) {
        // The same way from the same place, found a moment ago for someone else — the men a
        // barracks sends up after the squad, a march dropped while its squad fought. Seven in ten
        // searches in a big fight were one done already, and the rest of the squads stood waiting
        // their turn behind them.
        if (march.search == null) {
            if (level.gameTime - march.plannedAt < MIN_REPLAN_TICKS) return
            march.plannedAt = level.gameTime
            val dimension = level.dimension().location().toString()
            found.removeIf { level.gameTime - it.at > REUSE_TICKS }
            found.firstOrNull {
                march.route.isEmpty() &&
                it.dimension == dimension && it.from.closerThan(npc.blockPosition(), JOIN) && it.goal.closerThan(goal, SAME_GOAL)
            }?.let {
                march.progress.append(it.trail)
                march.complete = reachedWalkingGoal(it.complete, Vec3.atBottomCenterOf(it.trail.last()), goal)
                march.legsOnly = it.complete && !march.complete
                march.long = true
                DebugFlags.log(LogGroup.MARCH, "{} takes the way found from {} to {} ({} nodes)", squadName, it.from, it.goal, it.trail.size)
                return
            }
        }
        val from = march.route.lastOrNull()?.let(Vec3::atBottomCenterOf) ?: npc.position()
        val search = march.search ?: CellPlanner.search(Walking(level), from, Vec3.atBottomCenterOf(goal))
            ?.also {
                march.search = it
                march.searchStarted = level.gameTime
            }
        if (search == null) {
            march.legsOnly = needsLocalContinuation(march, npc)
            DebugFlags.log(LogGroup.MARCH, "{} no long route from {} (not on known open ground), {}", squadName, BlockPos.containing(from),
                if (march.legsOnly) "planning in legs" else "waiting for the frontier to load")
            return
        }
        if (!PlanBudget.advance(level, search)) return
        march.search = null
        val found = search.result()
        if (found == null || found.trail.size < 2) {
            march.legsOnly = needsLocalContinuation(march, npc)
            DebugFlags.log(LogGroup.MARCH, "{} no long route from {} to {} ({}), {}", squadName, BlockPos.containing(from), goal, search.stoppedBy,
                if (march.legsOnly) "planning in legs" else "keeping the previous route")
            return
        }
        val trail = found.trail.map { BlockPos.containing(it) }
        march.progress.append(trail)
        // CellPlanner's "complete" means its chosen endpoint is on known terrain. That can be
        // the foot of a cliff hundreds of blocks short of the actual order, not an arrival.
        march.complete = reachedWalkingGoal(found, goal)
        march.legsOnly = found.complete && !march.complete
        if (this.found.size >= MAX_REMEMBERED) this.found.removeAt(0)
        this.found += Found(level.dimension().location().toString(), trail.first(), goal, trail, found.complete, level.gameTime)
        march.long = true
        DebugFlags.log(LogGroup.MARCH,
            "{} long route from {} to {}: {} nodes, {} blocks, ends {} {} from the goal ({}; {} units over {} ticks)",
            squadName, trail.first(), goal, found.trail.size, found.length.toInt(), BlockPos.containing(found.landing),
            Math.hypot(found.landing.x - goal.x - 0.5, found.landing.z - goal.z - 0.5).toInt(), search.stoppedBy,
            search.expanded, level.gameTime - march.searchStarted + 1
        )
    }

    private fun needsLocalContinuation(march: March, npc: NpcEntity): Boolean =
        march.route.isEmpty() || npc.position().distanceToSqr(Vec3.atBottomCenterOf(march.route.last())) <= END_NODES * END_NODES

    internal fun reachedWalkingGoal(route: CellPlanner.Route, goal: BlockPos): Boolean =
        reachedWalkingGoal(route.complete, route.landing, goal)

    private fun reachedWalkingGoal(complete: Boolean, landing: Vec3, goal: BlockPos): Boolean =
        complete && Math.hypot(landing.x - goal.x - 0.5, landing.z - goal.z - 0.5) <= LEG_REACH

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
        val path = PathFinder(evaluator, NODES).findPath(region, npc, setOf(target), SEARCH_RANGE, LEG_REACH, 1f)
        val route = path?.let { p -> (0 until p.nodeCount).map { p.getNode(it).asBlockPos() } }.orEmpty()
        val end = route.lastOrNull()
        val progressed = end != null && Math.sqrt(end.distSqr(from)) >= NO_PROGRESS
        val reached = path?.canReach() == true
        if (progressed) {
            // Added on, not swapped in: the men still behind on the last leg keep their way.
            march.progress.append(route)
            march.failures = 0
            // Done once a straight leg found its way right to the goal; from there the ordinary
            // search takes each man to his own place.
            march.complete = straight && reached && len <= LEG
        } else {
            march.failures++
            march.misses++
        }
        DebugFlags.log(LogGroup.MARCH,
            "{} route {} leg from {} toward {} (goal {}, {} blocks, turn {}): {} nodes, reached={}, end={}, {} ms",
            squadName, march.origin, from, target, march.goal, len.toInt(), if (straight) 0 else march.failures,
            route.size, reached, end, "%.1f".format((System.nanoTime() - started) / 1.0e6)
        )
        if (march.misses >= GIVE_UP) {
            march.complete = true
            SquadMod.LOGGER.warn("{}: NPC {} ({}) cannot continue walking from {} toward {} after {} failed legs",
                squadName, npc.uuid, npc.npcClass, npc.blockPosition(), march.goal, march.misses)
        }
    }

    private const val TURNED_LEG_MIN = 48.0
    private const val GIVE_UP = 6

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
