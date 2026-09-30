package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.vehicle.WaterRoutes
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Whether a squad takes the boats near it, worked out once per order.
 *
 * For each boat within reach, the way over the water ([WaterRoutes]) to the bank nearest the goal,
 * and what the trip costs in blocks walked: the walk to the boat, the voyage at [BOAT_PACE] times a
 * man's pace, the walk in from the bank. Against that, walking the whole way — at best the straight
 * line, and with the water ahead to swim if the squad's route crosses some. A boat is taken when it
 * saves at least a [ADVANTAGE] share of that and actually goes somewhere; a river along the way to
 * the goal is sailed down, a lake crossed, and a pond next to a road left alone.
 *
 * The water searches run a few thousand columns a tick for everyone together ([NODES_PER_TICK]),
 * so a squad's boats are weighed over some ticks and the squad waits for the answer; a boat under
 * way looking for its next stretch keeps going along the last one meanwhile.
 */
object BoatTrips {
    /** A boat's trip: its way over the water to [goal], and what it costs in blocks walked. */
    class Trip(val boat: UUID, val goal: Vec3, val route: WaterRoutes.Route, val cost: Double)

    private class Decision(val stamp: Int, val goal: BlockPos, val madeAt: Long, val walk: Double, val pending: ArrayDeque<UUID>) {
        val trips = ArrayList<Trip>()
        /** The boat being weighed now, and its search under way. */
        var current: kotlin.Pair<Entity, WaterRoutes.Search>? = null
    }

    /** A boat's search for its next stretch of water, and when it was begun. */
    private class Replan(val goal: Vec3, val search: WaterRoutes.Search, val startedAt: Long)

    /** A boat covers ground this many times faster than a man walking. */
    private const val BOAT_PACE = 2.5
    /** A boat must save this share of the walk to be worth the boarding and landing. */
    private const val ADVANTAGE = 0.2
    /** Voyages shorter than this aren't worth getting in for. */
    private const val MIN_VOYAGE = 24.0
    /** Water on the squad's own route counts as this many times the straight walk. */
    private const val SWIM_PENALTY = 1.6
    /** A block of water on the straight line to the goal costs this many blocks of walking. */
    private const val SWIM_COST = 3.0
    /** Blocks between the samples of the straight line taken for [walkCost]. */
    private const val WALK_SAMPLE = 4.0
    /** Boats weighed per squad, nearest first. */
    private const val MAX_BOATS = 3
    private const val SEARCH_RADIUS = 60.0
    /** Nearer than this, nobody gets in a boat. */
    private const val MIN_TRIP = 40.0
    /** A goal moved less than this is the same one — an attack's goal is the enemy, and moves. */
    private const val SAME_GOAL = 16.0
    /** A decision is looked at again after this long — boats move, get taken, sink. */
    private const val DECISION_TICKS = 600L

    /** Columns of water searched per server tick, all searches together. */
    private const val NODES_PER_TICK = 2_500
    /** A boat's search nobody has come back for in this long is dropped. */
    private const val REPLAN_TICKS = 600L

    private val bySquad = HashMap<UUID, Decision>()
    private val replans = HashMap<UUID, Replan>()
    private var budgetTick = Long.MIN_VALUE
    private var budgetLeft = 0

    fun clearAll() {
        bySquad.clear()
        replans.clear()
        budgetTick = Long.MIN_VALUE
    }

    /** Runs [search] on with what's left of this tick's columns; true once it's over. */
    private fun advance(level: ServerLevel, search: WaterRoutes.Search): Boolean {
        if (budgetTick != level.gameTime) {
            budgetTick = level.gameTime
            budgetLeft = NODES_PER_TICK
        }
        if (budgetLeft <= 0) return false
        val before = search.expanded
        val done = search.step(budgetLeft)
        budgetLeft -= (search.expanded - before).coerceAtLeast(1)
        return done
    }

    /**
     * The boat trips worth taking for [npc]'s squad to [goal], cheapest first — empty when walking
     * (or a road vehicle) will do; null while the boats are still being weighed. [waterAhead] is
     * whether the squad's own route has water to cross; [usable] which boats could be taken at all.
     */
    fun tripsFor(npc: NpcEntity, goal: Vec3, waterAhead: Boolean, usable: (Entity) -> Boolean): List<Trip>? {
        val level = npc.level() as? ServerLevel ?: return emptyList()
        val squad = npc.currentSquad() ?: return emptyList()
        if (npc.position().distanceTo(goal) < MIN_TRIP) return emptyList()
        val goalPos = BlockPos.containing(goal)
        var decision = bySquad[squad.id]?.takeIf {
            it.stamp == squad.orderStamp && it.goal.closerThan(goalPos, SAME_GOAL) && level.gameTime - it.madeAt < DECISION_TICKS
        }
        if (decision == null) {
            val boats = Ports.vehicles.within(level, AABB.ofSize(npc.position(), SEARCH_RADIUS * 2, 32.0, SEARCH_RADIUS * 2)) {
                it.distanceToSqr(npc) <= SEARCH_RADIUS * SEARCH_RADIUS && usable(it)
            }.sortedBy { it.distanceToSqr(npc) }.take(MAX_BOATS)
            val walk = walkCost(level, npc.position(), goal) * if (waterAhead) SWIM_PENALTY else 1.0
            decision = Decision(squad.orderStamp, goalPos, level.gameTime, walk, ArrayDeque(boats.map { it.uuid }))
            bySquad[squad.id] = decision
        }
        while (true) {
            val current = decision.current ?: run {
                val id = decision.pending.removeFirstOrNull() ?: return decision.trips.sortedBy { it.cost }
                val boat = level.getEntity(id) ?: return@run null
                val search = WaterRoutes.search(level, boat.position(), boat.bbWidth / 2.0, goal)
                if (search == null) {
                    DebugFlags.log("[boat-debug] {} boat {}: not afloat", squad.name, id.toString().take(8))
                    return@run null
                }
                (boat to search).also { decision.current = it }
            } ?: continue
            if (!advance(level, current.second)) return null
            decision.current = null
            weigh(npc, current.first, goal, current.second, decision, squad.name)
        }
    }

    /**
     * Walking to [goal], in blocks: the straight line, with the stretches of it that are water
     * counted [SWIM_COST] times over — that's either swimming or a long way round. Taking the line
     * as dry ground had a squad set down 400 blocks short of its goal, on a lake, decide the rest
     * was a walk, and swim it.
     */
    private fun walkCost(level: ServerLevel, from: Vec3, goal: Vec3): Double {
        val dist = Math.hypot(goal.x - from.x, goal.z - from.z)
        val steps = Math.ceil(dist / WALK_SAMPLE).toInt().coerceAtLeast(1)
        var wet = 0
        var seen = 0
        val cursor = BlockPos.MutableBlockPos()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = Math.floor(from.x + (goal.x - from.x) * t).toInt()
            val z = Math.floor(from.z + (goal.z - from.z) * t).toInt()
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: continue
            val top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15)
            seen++
            if (level.getFluidState(cursor.set(x, top, z)).`is`(net.minecraft.tags.FluidTags.WATER)) wet++
        }
        val wetShare = if (seen == 0) 0.0 else wet.toDouble() / seen
        return dist * (1 + wetShare * (SWIM_COST - 1))
    }

    /** The trip [boat] is on, if some squad's decision took it. */
    fun tripOf(boat: UUID): Trip? = bySquad.values.firstNotNullOfOrNull { d -> d.trips.firstOrNull { it.boat == boat } }

    /**
     * A fresh way for [boat] from where it is now to [goal] — after it was knocked off the old
     * one. Null when no search can run this tick (another one had it) or none was found.
     */
    fun replan(boat: Entity, goal: Vec3): WaterRoutes.Route? {
        val level = boat.level() as? ServerLevel ?: return null
        replans.entries.removeIf { level.gameTime - it.value.startedAt > REPLAN_TICKS }
        val replan = replans[boat.uuid]?.takeIf { it.goal == goal } ?: run {
            val search = WaterRoutes.search(level, boat.position(), boat.bbWidth / 2.0, goal) ?: return null
            Replan(goal, search, level.gameTime).also { replans[boat.uuid] = it }
        }
        if (!advance(level, replan.search)) return null
        replans.remove(boat.uuid)
        DebugFlags.log(
            "[boat-debug] boat {} searched {} columns over {} ticks ({})", boat.uuid.toString().take(8),
            replan.search.expanded, level.gameTime - replan.startedAt + 1, replan.search.stoppedBy
        )
        return replan.search.result()
    }

    private fun weigh(npc: NpcEntity, boat: Entity, goal: Vec3, search: WaterRoutes.Search, decision: Decision, squadName: String) {
        val route = search.result()
        val how = "${search.expanded} columns, ${search.stoppedBy}"
        if (route == null) {
            DebugFlags.log("[boat-debug] {} boat {}: no way over the water to a bank ({})", squadName, boat.uuid.toString().take(8), how)
            return
        }
        val cost = npc.position().distanceTo(boat.position()) + route.length / BOAT_PACE + route.shore.distanceTo(goal)
        val worth = route.length >= MIN_VOYAGE && cost <= decision.walk * (1 - ADVANTAGE)
        DebugFlags.log(
            "[boat-debug] {} boat {}: voyage {} {} {} ({} from goal), trip {} vs walk {} -> {} ({})",
            squadName, boat.uuid.toString().take(8), route.length.toInt(), if (route.complete) "to land at" else "and on past",
            BlockPos.containing(route.shore), route.shore.distanceTo(goal).toInt(), cost.toInt(), decision.walk.toInt(),
            if (worth) "take it" else "walk", how
        )
        if (worth) decision.trips += Trip(boat.uuid, goal, route, cost)
    }
}
