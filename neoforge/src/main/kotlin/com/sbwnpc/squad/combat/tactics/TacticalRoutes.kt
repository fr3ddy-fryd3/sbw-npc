package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/** Short route legs along the walked route keep a distant destination from routing straight through the enemy. */
object TacticalRoutes {
    /** Reserves and observers still have to reach their assigned area before holding it. */
    val TRANSIT_JOBS = TacticalJob.RUNNING + setOf(TacticalJob.SEARCH, TacticalJob.RESERVE, TacticalJob.OBSERVE)

    /**
     * The next leg along the way a man can actually walk to [TacticalTask.anchor].
     *
     * A straight leg ran into whatever lay between: in-game 16.10 an assault 40 blocks from its
     * point had a lake on the line, every post probed there was water, and the men stood on the
     * shore for minutes. The route is planned once, with room to go round, and followed until
     * the man strays from it.
     */
    fun routeLeg(entity: NpcEntity, task: TacticalTask): Vec3 {
        val from = entity.position()
        val straight = leg(from, task)
        if (straight == task.anchor) return straight
        val route = task.route?.takeIf { nodes -> nodes.any { it.distanceTo(from) <= ROUTE_DEVIATION } } ?: plan(entity, task) ?: task.route ?: return straight
        val start = route.indices.minBy { route[it].distanceToSqr(from) }
        var walked = 0.0
        for (index in start + 1 until route.size) {
            walked += route[index].distanceTo(route[index - 1])
            if (walked >= ROUTE_LEG) return route[index]
        }
        return route.last()
    }

    private fun plan(entity: NpcEntity, task: TacticalTask): List<Vec3>? {
        val now = entity.level().gameTime
        val navigation = entity.navigation as? VehicleAwareNavigation ?: return null
        if (now < task.nextRoutePlan || !TacticalBudget.path(entity)) return null
        task.nextRoutePlan = now + ROUTE_RETRY_TICKS
        val path = TacticalBudget.measure(entity) { navigation.createPositionPath(setOf(VehicleAwareNavigation.walkingNode(task.anchor)), allowDetour = true) } ?: return null
        val route = route(path, entity.position(), task.anchor)
        if (route == null) {
            DebugFlags.log(LogGroup.PATH, "[tactics] {} plan={} event=route_leg_rejected reaches={} nodes={} end={} anchor={}", entity.uuid, task.plan, path.canReach(), path.nodeCount, path.endNode?.asBlockPos(), task.anchor)
        }
        return route?.also { task.route = it }
    }

    /**
     * The walked route [path] gives from [from] to [anchor], or null when it is no way there.
     *
     * Vanilla A* returns a path to the nearest node it found when the anchor is out of reach. One
     * that ends where the man stands puts his leg on his own spot; in-game 17.10 movers stood on such
     * legs in an endless arrive-and-search loop (#50). A partial path counts only while it gets him
     * at least a leg nearer the anchor.
     */
    internal fun route(path: Path, from: Vec3, anchor: Vec3): List<Vec3>? {
        val nodes = (0 until path.nodeCount).map { path.getNode(it).let { node -> Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5) } }
        if (nodes.size < 2) return null
        if (!path.canReach() && nodes.last().distanceTo(anchor) > from.distanceTo(anchor) - ROUTE_LEG) return null
        return nodes
    }

    /** Route length of one leg: about the straight leg's 24 blocks. */
    private const val ROUTE_LEG = 20.0

    /** Farther than this from every node of its route, a man has a new route planned. */
    private const val ROUTE_DEVIATION = 8.0
    private const val ROUTE_RETRY_TICKS = 100L

    fun leg(from: Vec3, task: TacticalTask): Vec3 {
        val delta = task.anchor.subtract(from)
        return if (delta.length() > 24.0) from.add(delta.normalize().scale(24.0)) else task.anchor
    }
}
