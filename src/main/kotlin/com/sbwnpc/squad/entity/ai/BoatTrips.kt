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
 * One water search per server tick for everyone, so a squad's boats are weighed over a few ticks
 * and the squad waits for the answer.
 */
object BoatTrips {
    /** A boat's trip: its way over the water to [goal], and what it costs in blocks walked. */
    class Trip(val boat: UUID, val goal: Vec3, val route: WaterRoutes.Route, val cost: Double)

    private class Decision(val stamp: Int, val goal: BlockPos, val madeAt: Long, val walk: Double, val pending: ArrayDeque<UUID>) {
        val trips = ArrayList<Trip>()
    }

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

    private val bySquad = HashMap<UUID, Decision>()
    private var lastPlanTick = Long.MIN_VALUE

    fun clearAll() {
        bySquad.clear()
        lastPlanTick = Long.MIN_VALUE
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
        while (decision.pending.isNotEmpty()) {
            if (lastPlanTick == level.gameTime) return null
            lastPlanTick = level.gameTime
            val id = decision.pending.removeFirst()
            val boat = level.getEntity(id) ?: continue
            weigh(level, npc, boat, goal, decision, squad.name)
        }
        return decision.trips.sortedBy { it.cost }
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
        if (lastPlanTick == level.gameTime) return null
        lastPlanTick = level.gameTime
        return WaterRoutes.plan(level, boat.position(), boat.bbWidth / 2.0, goal)
    }

    private fun weigh(level: ServerLevel, npc: NpcEntity, boat: Entity, goal: Vec3, decision: Decision, squadName: String) {
        val started = System.nanoTime()
        val route = WaterRoutes.plan(level, boat.position(), boat.bbWidth / 2.0, goal)
        val ms = (System.nanoTime() - started) / 1.0e6
        if (route == null) {
            DebugFlags.log("[boat-debug] {} boat {}: no way over the water to a bank ({} ms)", squadName, boat.uuid.toString().take(8), "%.1f".format(ms))
            return
        }
        val cost = npc.position().distanceTo(boat.position()) + route.length / BOAT_PACE + route.shore.distanceTo(goal)
        val worth = route.length >= MIN_VOYAGE && cost <= decision.walk * (1 - ADVANTAGE)
        DebugFlags.log(
            "[boat-debug] {} boat {}: voyage {} to land at {} ({} from goal), trip {} vs walk {} -> {} ({} ms)",
            squadName, boat.uuid.toString().take(8), route.length.toInt(), BlockPos.containing(route.shore),
            route.shore.distanceTo(goal).toInt(), cost.toInt(), decision.walk.toInt(), if (worth) "take it" else "walk", "%.1f".format(ms)
        )
        if (worth) decision.trips += Trip(boat.uuid, goal, route, cost)
    }
}
