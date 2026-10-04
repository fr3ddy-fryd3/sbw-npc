package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Ports
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.Level
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB

/**
 * Ground navigation that treats parked vehicles as terrain.
 *
 * Minecraft's pathfinder reads blocks and nothing else — entities are not obstacles to it, which is
 * why third-party navigators list "avoids entities" as a feature of their own. So a mob asked to
 * walk past an APC plots a route straight through the space the APC occupies, and only the
 * vehicle's collision decides what happens next: it gets shoved against the hull, or rides up onto
 * the roof. No amount of care in *choosing* a destination fixes that, because the problem is the
 * walk, not the target.
 *
 * There is no vanilla hook for "this entity is solid to pathfinding", so the node evaluator has to
 * be told directly.
 */
class VehicleAwareNavigation(mob: Mob, level: Level) : GroundPathNavigation(mob, level) {
    /** Tactical posts need their exact height, rather than a march waypoint on the surface. */
    fun createPositionPath(pos: BlockPos): Path? {
        if (!canPlan) return null
        val saved=nodeMultiplier
        super.setMaxVisitedNodesMultiplier(maxOf(1f,saved))
        try {
            val range=(kotlin.math.sqrt(pos.distToCenterSqr(mob.x,mob.y,mob.z))+8.0).coerceIn(48.0,80.0).toFloat()
            return createPath(setOf(pos),8,false,0,range)
        } finally {
            super.setMaxVisitedNodesMultiplier(saved)
        }
    }

    override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
        val evaluator = VehicleAwareNodeEvaluator()
        evaluator.setCanPassDoors(true)
        this.nodeEvaluator = evaluator
        return PathFinder(evaluator, maxVisitedNodes)
    }

    /**
     * A far destination gets one long leg at a time, planned with room to find a way round.
     *
     * Vanilla ground navigation gives up outright on a target in a chunk that isn't loaded (no
     * path at all — a squad ordered from the map to a point hundreds of blocks off never took a
     * step), and otherwise searches only the mob's follow range (48) with a node budget halved
     * while it isn't fighting. A leg that short, planned straight at the goal, ended at the foot of
     * whatever hill was in the way, and the squad stood pressed against the slope asking for the
     * same dead end every second.
     *
     * A squad member follows its squad's route ([SquadMarch]). Anyone else — or a member before
     * the route is planned — past [NEAR_RANGE] gets a leg that aims up to [FAR_RANGE] blocks toward the goal (as far as loaded
     * ground goes), is searched over that whole range with [FAR_NODE_MULTIPLIER] times the nodes,
     * and is walked to its end before the next one is planned — callers ask again every second,
     * and re-planning a search this size that often for every man in a squad would cost far more
     * than the walk needs.
     */
    override fun createPath(pos: BlockPos, accuracy: Int): Path? {
        branch = "?"
        // Vanilla plans nothing while he's off the ground, and an empty answer then is no news
        // about the way — so the branch state (the goals the path being walked was planned to)
        // is left as it is, not set to a goal nothing was planned to.
        val result = if (canPlan) choosePath(pos, accuracy) else { branch = "in the air"; null }
        (mob as? com.sbwnpc.squad.entity.NpcEntity)?.let { npc ->
            val far = pos.distToCenterSqr(mob.x, mob.y, mob.z) > NOWHERE_MIN_DISTANCE * NOWHERE_MIN_DISTANCE
            val end = result?.endNode?.asBlockPos()
            // A reachable local waypoint can still be the block he already occupies, while the
            // actual order is far away. Null ground searches also need physical stuck recovery.
            // Airborne requests say nothing about whether the path can advance.
            if (canPlan) {
                val nowhere = far && (result == null || end != null && end.distManhattan(mob.blockPosition()) <= 1)
                npc.notePathGoesNowhere(pos, nowhere)
            }
        }
        // No path on the ground is rare and is what a caller gives up on — never skipped.
        if (com.sbwnpc.squad.combat.DebugFlags.on(com.sbwnpc.squad.combat.LogGroup.PATH) && (result == null && canPlan || mob.tickCount - lastPathLogTick >= PATH_LOG_TICKS)) {
            lastPathLogTick = mob.tickCount
            com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.PATH,
                "{} at {} asked {} ({} blocks) branch={} target={} -> {}",
                mob.uuid.toString().take(8), mob.blockPosition(), pos,
                Math.sqrt(pos.distToCenterSqr(mob.x, mob.y, mob.z)).toInt(), branch, (mob as? com.sbwnpc.squad.entity.NpcEntity)?.target != null,
                result?.let { "nodes=${it.nodeCount} reach=${it.canReach()} end=${it.endNode?.asBlockPos()} short=${"%.1f".format(it.distToTarget)}" }
                    ?: "null (canUpdate=${canUpdatePath()} ground=${mob.onGround()} liquid=${mob.isInLiquid} passenger=${mob.isPassenger} y=${"%.2f".format(mob.y)})"
            )
        }
        return result
    }

    private var branch = "?"

    /** Whether a path can be planned now — not mid-jump or falling (vanilla's own condition). */
    val canPlan: Boolean get() = canUpdatePath()

    /**
     * Vanilla re-plans the path being walked when a block next to it changes — which in a fight
     * is all the time — by dropping it first and asking again. Mid-jump the answer is empty, and
     * a man walking a good path was left with none: whoever had asked for it once took that for
     * the walk being over. Off the ground, vanilla's own deferred re-plan is used instead: its
     * tick asks again every tick until he lands.
     */
    override fun recomputePath() {
        if (!canPlan && targetPos != null) {
            if (!hasDelayedRecomputation) traceAir("re-plan to $targetPos put off till he lands")
            hasDelayedRecomputation = true
            return
        }
        super.recomputePath()
    }

    /** An empty answer mid-jump keeps the path he's walking; the caller still hears there was none. */
    override fun moveTo(path: Path?, speed: Double): Boolean {
        if (path == null && !canPlan && this.path?.isDone == false) {
            traceAir("asked mid-jump, keeps walking the path to ${this.path?.target}")
            return false
        }
        if (path == null || !path.sameAs(this.path)) traceHandover(if (path == null) "path dropped, none in its place" else "new path to ${path.target}")
        return super.moveTo(path, speed)
    }

    override fun stop() {
        if (path?.isDone == false) traceHandover("stopped")
        super.stop()
    }

    override fun tick() {
        super.tick()
        traceWalk()
    }

    private fun investigating() = (mob as? com.sbwnpc.squad.entity.NpcEntity)?.isAlert() == true &&
        com.sbwnpc.squad.combat.DebugFlags.on(com.sbwnpc.squad.combat.LogGroup.PATH)

    /** While he's off to look at a noise: who took his path away or swapped it, from the frames above. */
    private fun traceHandover(what: String) {
        if (!investigating()) return
        val from = Throwable().stackTrace.asSequence()
            .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            // Only the plumbing — vanilla's own stuck check (PathNavigation.doStuckDetection) stays.
            .filterNot { frame -> PLUMBING.any { frame.startsWith(it) } }
            .take(4).joinToString(" <- ")
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.PATH, "{} at {} {} (was to {}, node {}/{}) by {}",
            mob.uuid.toString().take(8), mob.blockPosition(), what, path?.target, path?.nextNodeIndex, path?.nodeCount, from
        )
    }

    private var lastWalkLogTick = Int.MIN_VALUE / 2

    /** Once a second while he's off to look at a noise: is he getting along the path, or standing. */
    private fun traceWalk() {
        val current = path ?: return
        if (current.isDone || mob.tickCount - lastWalkLogTick < WALK_LOG_TICKS || !investigating()) return
        lastWalkLogTick = mob.tickCount
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.PATH, "{} walking at {} node {}/{} next {} to {} speed {} ground={}",
            mob.uuid.toString().take(8), mob.blockPosition(), current.nextNodeIndex, current.nodeCount,
            current.nextNodePos, current.target, "%.2f".format(mob.deltaMovement.horizontalDistance()), mob.onGround()
        )
    }

    private var lastAirLogTick = Int.MIN_VALUE / 2

    /** Once a jump — callers ask every tick while he's up. */
    private fun traceAir(what: String) {
        if (mob.tickCount - lastAirLogTick < AIR_LOG_TICKS) return
        lastAirLogTick = mob.tickCount
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.PATH, "{} at {} y={}: {}",
            mob.uuid.toString().take(8), mob.blockPosition(), "%.2f".format(mob.y), what
        )
    }
    private var lastPathLogTick = Int.MIN_VALUE / 2

    private fun choosePath(pos: BlockPos, accuracy: Int): Path? {
        val dx = pos.x + 0.5 - mob.x
        val dz = pos.z + 0.5 - mob.z
        val len = Math.sqrt(dx * dx + dz * dz)
        // At the wheel or on a bench, a man's squad route is a footpath — no way for a vehicle.
        // The squad march and resupply trips use long routes. Brief errands (investigating a
        // noise, returning to a mortar) still use local legs rather than wait for a long search.
        // Resupplying men follow the route itself, not a formation around the squad's lead.
        val npc = (mob as? com.sbwnpc.squad.entity.NpcEntity)
            ?.takeIf { !it.recoveringNavigation && it.vehicle == null && (resupplyTrip(it) ||
                it.homeCenter()?.let { home -> pos.closerThan(BlockPos.containing(home), MARCH_GOAL_RANGE) } == true) }
        val formation = npc?.let { !resupplyTrip(it) } ?: true
        if (len <= NEAR_RANGE && level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) != null) {
            farGoal = null
            branch = "near"
            reusable(pos)?.let { return it }
            nearGoal = pos
            val near = super.createPath(pos, accuracy)
            // Close, but the short search can't get there — a hill in the way, most likely. Out of
            // a fight, the squad's big search looks for the way round.
            if (near != null && !near.canReach() && near.distToTarget > SHORT_OF_GOAL && npc != null &&
                (npc.target == null || resupplyTrip(npc))) {
                SquadMarch.waypointFor(npc, pos, formation)?.let {
                    branch = "near-route ${it.route}"
                    nearGoal = null
                    return super.createPath(it.route, accuracy)
                }
            }
            return near
        }
        // In a squad: its shared route, walked with the ordinary short search.
        npc?.let {
            SquadMarch.waypointFor(it, pos, formation)?.let { waypoint ->
                farGoal = null
                // His place in the formation if he can get there; the route itself otherwise.
                waypoint.formation?.let { spot ->
                    reusable(spot, MARCH_DRIFT)?.let { return it }
                    nearGoal = spot
                    val path = super.createPath(spot, accuracy)
                    if (path != null && (path.canReach() || path.distToTarget <= SHORT_OF_GOAL)) {
                        branch = "formation $spot"
                        return path
                    }
                }
                branch = "route ${waypoint.route}"
                reusable(waypoint.route, MARCH_DRIFT)?.let { return it }
                nearGoal = waypoint.route
                return super.createPath(waypoint.route, accuracy)
            }
        }
        val current = path
        val goal = farGoal
        branch = "leg"
        nearGoal = null
        if (current != null && !current.isDone && goal != null && goal.closerThan(pos, GOAL_DRIFT)) return current
        farGoal = pos
        val leg = legToward(dx, dz, len) ?: run { branch = "leg (no loaded ground toward the goal)"; return null }
        val saved = nodeMultiplier
        super.setMaxVisitedNodesMultiplier(FAR_NODE_MULTIPLIER)
        try {
            return createPath(setOf(leg), 8, false, maxOf(accuracy, LEG_REACH), FAR_RANGE.toFloat())
        } finally {
            super.setMaxVisitedNodesMultiplier(saved)
        }
    }

    /** The farthest point on loaded ground up to [FAR_RANGE] toward the goal, on the surface. */
    private fun legToward(dx: Double, dz: Double, len: Double): BlockPos? {
        var reach = minOf(len, FAR_RANGE - 8.0)
        while (reach >= MIN_LEG) {
            val x = Math.floor(mob.x + dx / len * reach).toInt()
            val z = Math.floor(mob.z + dz / len * reach).toInt()
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4)
            if (chunk != null) {
                val y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1
                return BlockPos(x, y, z)
            }
            reach -= 8.0
        }
        return null
    }

    private fun resupplyTrip(npc: com.sbwnpc.squad.entity.NpcEntity): Boolean =
        npc.resupplying || (npc.servingMortar && npc.mortarShellsLeft <= 0)

    /** What the path being walked was last planned to, on the near, formation and route branches. */
    private var nearGoal: BlockPos? = null

    /**
     * The path being walked, if it's still going and was planned to within [drift] of [goal].
     * A formation place is worked out from where its man stands and moves a block or so every
     * second, and vanilla keeps a path only for the very same block — so every member of every
     * squad was running a fresh search each second, the biggest cost on the server in a large
     * fight. A path a block or two off gets him there just the same; once it's done, or dropped
     * as stuck (vanilla clears it before replanning), the next ask searches anew.
     */
    private fun reusable(goal: BlockPos, drift: Double = NEAR_DRIFT): Path? {
        val current = path ?: return null
        val planned = nearGoal ?: return null
        return current.takeIf { !it.isDone && planned.closerThan(goal, drift) }
    }

    /** The goal of the leg being walked; a new one within [GOAL_DRIFT] of it is the same goal. */
    private var farGoal: BlockPos? = null
    /** What the owner last set the node budget to — restored after a long leg's bigger search. */
    private var nodeMultiplier = 1f

    override fun setMaxVisitedNodesMultiplier(multiplier: Float) {
        nodeMultiplier = multiplier
        super.setMaxVisitedNodesMultiplier(multiplier)
    }

    override fun resetMaxVisitedNodesMultiplier() {
        nodeMultiplier = 1f
        super.resetMaxVisitedNodesMultiplier()
    }

    private companion object {
        /** Closer than this, a path ending where it stands is just being there. */
        const val NOWHERE_MIN_DISTANCE = 3.0
        /** The NPC's own follow range: nearer than this, vanilla's own search is enough. */
        const val NEAR_RANGE = 48.0
        /** A goal this near the squad's objective is the squad's march: a place in its formation there. */
        const val MARCH_GOAL_RANGE = 48.0
        const val FAR_RANGE = 100.0
        /** 768 nodes at follow range 48 — three times that for a search twice as far. */
        const val FAR_NODE_MULTIPLIER = 3f
        const val MIN_LEG = 8.0
        /** A leg ending this near its end point got there — see SquadMarch's LEG_REACH. */
        const val LEG_REACH = 8
        const val GOAL_DRIFT = 4.0
        /** A near goal or formation place moved less than this keeps the path already walked. */
        const val NEAR_DRIFT = 2.0
        /**
         * The same on the march, for both his formation place and the route point. Both sit sixteen
         * route nodes ahead and move on at the squad's pace — faster than [NEAR_DRIFT] a second —
         * so every man ran a fresh search each second: the biggest path cost left after the long
         * legs were fixed. A place eight blocks behind where it has moved to still leads him the
         * same way, and the next ask after he gets there catches up.
         */
        const val MARCH_DRIFT = 8.0
        /** A path ending nearer its goal than this just can't stand on the exact block. */
        const val SHORT_OF_GOAL = 4f
        const val PATH_LOG_TICKS = 40
        const val AIR_LOG_TICKS = 10
        const val WALK_LOG_TICKS = 20
        val PLUMBING = listOf(
            "VehicleAwareNavigation.traceHandover", "VehicleAwareNavigation.moveTo", "VehicleAwareNavigation.stop",
            "PathNavigation.moveTo"
        )
    }
}

internal open class VehicleAwareNodeEvaluator : WalkNodeEvaluator() {
    // Vanilla caches the aggregate type of a mob-sized node, but neighboring nodes repeatedly
    // evaluate overlapping cells and their 24 surrounding hazards. Keep the final cell type
    // for this search only; raw block types in PathfindingContext do not cache those hazards.
    private val cellTypes = Long2ObjectOpenHashMap<PathType>()
    /** Vehicles near the mob, each with the box round its hull for a quick first test. */
    private var hulls: List<Pair<net.minecraft.world.entity.Entity, AABB>> = emptyList()
    private var standingOn: BlockPos? = null
    private var start: BlockPos? = null
    private var escapingCanopy = false

    /**
     * One entity query for the whole path computation. [done] drops it again, and the evaluator's
     * own per-position cache is cleared there too, so a vehicle that drives off doesn't leave
     * phantom walls behind.
     */
    override fun prepare(level: PathNavigationRegion, mob: Mob) {
        cellTypes.clear()
        super.prepare(level, mob)
        val ridden = mob.vehicle
        hulls = Ports.vehicles
            .within(mob.level(), mob.boundingBox.inflate(SEARCH_RADIUS)) { vehicle ->
                Ports.vehicles.isOperational(vehicle) && vehicle !== ridden
            }
            // The whole hull: a BMP's box is a 3.6-block square round its middle, its hull twice as
            // long, and paths planned through the nose left men pressed against it.
            .map { it to Ports.vehicles.hull(it).inflate(CLEARANCE) }
            // One he's already up against or inside of doesn't count: every way out of it would
            // be a wall, and he'd stand there with a one-node path while it waits for him.
            .filterNot { (vehicle, _) -> Ports.vehicles.occupies(vehicle, mob.boundingBox, CLEARANCE) }
        // Whatever the mob is standing in stays passable. Blocking it would leave the path with no
        // valid start at all, which is precisely the situation of a mob that has already been
        // pushed up onto a hull and now needs a route off it.
        standingOn = if (hulls.isEmpty()) null else mob.blockPosition()
        start = mob.blockPosition()
        // A man already on a crown needs a way off it, but ground routes must not climb crowns.
        escapingCanopy = (-1..0).any { dy ->
            WalkingClearance.leaves(level.getBlockState(mob.blockPosition().offset(0, dy, 0)))
        }
    }

    override fun done() {
        cellTypes.clear()
        hulls = emptyList()
        standingOn = null
        start = null
        escapingCanopy = false
        super.done()
    }

    /**
     * Blocks that fill the upper part of their space without being full blocks — an azalea bush
     * is its top half and a thin stem — are open ground to vanilla (only a full collision box
     * counts as in the way), so paths ran straight through them and men walked into the bush with
     * no jump to get over it. Anything a man can't step over is solid here, and stood on like it.
     */
    override fun getPathType(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        // The public evaluator API can also be queried outside a prepared path search. Those
        // independent queries must see current terrain rather than retain a cached answer.
        if (context !== currentContext) return calculatePathType(context, x, y, z)
        val key = BlockPos.asLong(x, y, z)
        cellTypes.get(key)?.let { return it }
        return calculatePathType(context, x, y, z).also { cellTypes.put(key, it) }
    }

    private fun calculatePathType(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        if (bodyHigh(context, x, y, z)) return PathType.BLOCKED
        val type = super.getPathType(context, x, y, z)
        if (type == PathType.OPEN) {
            val below = BlockPos(x, y - 1, z)
            val state = context.getBlockState(below)
            // Partial roots need a raised walking node even when they are only half a block high.
            if (!state.isAir && !state.getCollisionShape(context.level(), below).isEmpty) return PathType.WALKABLE
        }
        return type
    }

    private fun bodyHigh(context: PathfindingContext, x: Int, y: Int, z: Int): Boolean {
        // Wherever the mob already is stays passable, or a man stuck half inside one has no start.
        start?.let { if (it.x == x && it.y == y && it.z == z) return false }
        if (context.getPathTypeFromState(x, y, z) != PathType.OPEN) return false
        val pos = BlockPos(x, y, z)
        val state = context.getBlockState(pos)
        if (state.isAir) return false
        val shape = state.getCollisionShape(context.level(), pos)
        return !shape.isEmpty && shape.max(net.minecraft.core.Direction.Axis.Y) > STEP_OVER
    }

    override fun getPathTypeOfMob(context: PathfindingContext, x: Int, y: Int, z: Int, mob: Mob): PathType {
        if (hulls.isNotEmpty() && !isStandingOn(x, y, z) && occupied(x, y, z)) {
            return PathType.BLOCKED
        }
        val type = super.getPathTypeOfMob(context, x, y, z, mob)
        if (mob.getPathfindingMalus(type) < 0f) return type
        val pos = BlockPos(x, y, z)
        val support = context.getBlockState(pos.below())
        if (WalkingClearance.leaves(support) && (!escapingCanopy || y > (start?.y ?: y) + 1)) return PathType.BLOCKED
        // Vanilla normally checks collisions only for fences/doors and jumps. A modded branch
        // can occupy an otherwise empty neighboring node, and roots can catch the legs there.
        // Keep the start usable for an NPC who was already pushed into an obstacle.
        val origin = start
        if (origin != null && origin.x == x && origin.z == z && kotlin.math.abs(origin.y - y) <= 1) return type
        val floor = if (type == PathType.OPEN || type == PathType.WATER) y.toDouble()
            else getFloorLevel(pos)
        val box = WalkingClearance.body(x, floor, z, mob.bbWidth, mob.bbHeight)
        return if (context.level().noBlockCollision(mob, box)) type else PathType.BLOCKED
    }

    private fun isStandingOn(x: Int, y: Int, z: Int): Boolean {
        val pos = standingOn ?: return false
        return pos.x == x && pos.z == z && Math.abs(pos.y - y) <= 1
    }

    private fun occupied(x: Int, y: Int, z: Int): Boolean {
        val node = AABB(
            x.toDouble(), y.toDouble(), z.toDouble(),
            x + 1.0, y + 1.0, z + 1.0
        )
        // The box round a hull turned across the axes is far bigger than the hull — a BMP at an
        // angle boxed in men standing well clear of it — so only the hull itself blocks.
        return hulls.any { (vehicle, box) -> box.intersects(node) && Ports.vehicles.occupies(vehicle, node, CLEARANCE) }
    }

    private companion object {
        const val SEARCH_RADIUS = 24.0
        /** Taller than this (a carpet, a snow layer, a bottom slab are lower) can't be stepped over. */
        const val STEP_OVER = 0.5
        /** Keeps routes from hugging the hull close enough to catch on it. */
        const val CLEARANCE = 0.3
    }
}
