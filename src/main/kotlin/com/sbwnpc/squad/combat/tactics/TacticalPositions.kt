package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Local positions are validated through the existing navigation and collider raycasts. */
object TacticalPositions {
    enum class Outcome { FOUND, DEFERRED, UNREACHABLE }
    data class Result(val outcome: Outcome, val position: Vec3? = null, val rejections: Map<String, Int> = emptyMap())

    fun find(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot): Result {
        val level = entity.level() as ServerLevel
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return Result(Outcome.DEFERRED)
        val overwatch = task.job == TacticalJob.OVERWATCH
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0 +
            if (overwatch) DefensiveOverwatch.RADIUS else 0.0
        val heldPosition = existingCoverPosition(entity, task, view, level, homeRadius)
        if (heldPosition != null) return finish(task, heldPosition)
        if (!TickBudget.hasRaycasts(level)) return Result(Outcome.DEFERRED)

        val context = prepareSearch(entity, task, view, level, homeRadius, overwatch)
        if (!collectCandidates(context)) return Result(Outcome.DEFERRED)
        return findReachablePosition(context)
    }

    /** Query results belong to this attempt; cursors and scores remain on task.search across ticks. */
    private data class SearchContext(
        val entity: NpcEntity,
        val task: TacticalTask,
        val view: TacticalSnapshot,
        val level: ServerLevel,
        val homeRadius: Double,
        val overwatch: Boolean,
        val taken: List<Vec3>,
        val threats: List<TacticalContact>,
        val hulls: List<AABB>,
        val localAnchor: Vec3,
        val search: TacticalPositionSearch,
        val policy: TacticalPositionPolicy,
        val covered: Boolean,
    ) {
        val focus = task.focus
    }

    private fun existingCoverPosition(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, level: ServerLevel, homeRadius: Double): Vec3? {
        val member = view.members.firstOrNull { it.id == entity.uuid }
        val canKeepPosition = task.job == TacticalJob.COVER && member != null && TacticalEvidence.covers(member, task) &&
            member.position.distanceTo(task.anchor) <= 10.0 &&
            TacticalRules.withinOrder(view, member.position, homeRadius) && !GrenadeHazard.threatens(level, member.position)
        return if (canKeepPosition) member?.position else null
    }

    private fun prepareSearch(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, level: ServerLevel, homeRadius: Double, overwatch: Boolean): SearchContext {
        val taken = FiringSpots.nearbyWithBodies(level, entity, 64.0)
        val threats = view.visible.sortedByDescending { it.priority }.take(3)
        val hulls = Sightline.vehicleHulls(level, entity.boundingBox.inflate(64.0), entity, entity.target)
        val localAnchor = task.staging ?: if (task.job in RUNNING_JOBS + TacticalJob.SEARCH) {
            TacticalRoutes.leg(entity.position(), task)
        } else {
            task.anchor
        }
        if (task.search?.origin?.distanceTo(entity.position())?.let { it > 4.0 } == true) task.search = null
        if (overwatch && task.search == null) entity.navigation.stop()
        val search = task.search ?: TacticalPositionSearch(
            entity.position(),
            if (overwatch) DefensiveOverwatch.probes(task.anchor) else OFFSETS.map { localAnchor.add(it) },
        ).also { task.search = it }
        val plan = entity.currentSquad()?.tactics?.plan
        val policy = plan?.behavior?.positions ?: DefaultTacticalPositions
        val covered = plan != null && view.members.count { member ->
            plan.tasks[member.id]?.job == TacticalJob.COVER && TacticalEvidence.firesCover(member, plan.tasks[member.id])
        } >= TacticalFlanks.requiredCover(view)
        return SearchContext(entity, task, view, level, homeRadius, overwatch, taken, threats, hulls, localAnchor, search, policy, covered)
    }

    /** The probe cursor advances before validation, including rejected candidates. */
    private fun collectCandidates(context: SearchContext): Boolean {
        val search = context.search
        var probesThisTick = 0
        while (search.probeIndex < search.probes.size) {
            if (!TickBudget.hasRaycasts(context.level) || probesThisTick++ >= MAX_PROBES_PER_TICK) return false
            val raw = search.probes[search.probeIndex++]
            scoreCandidate(context, raw)
        }
        return true
    }

    private fun scoreCandidate(context: SearchContext, raw: Vec3) {
        val entity = context.entity
        val task = context.task
        val level = context.level
        val search = context.search
        val focus = context.focus
        val block = BlockPos.containing(raw)
        if (!level.chunkSource.hasChunk(block.x shr 4, block.z shr 4)) {
            search.reject("unloaded")
            return
        }
        val candidate = Terrain.feetAt(level, raw, if (context.overwatch) 9 else 12) {
            level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(it))
        }
        if (candidate == null) {
            search.reject("terrain")
            return
        }
        if (!search.visited.add(candidate)) return
        if (!TacticalRules.withinOrder(context.view, candidate, context.homeRadius)) {
            search.reject("order")
            return
        }
        if (context.overwatch && !DefensiveOverwatch.within(task.anchor, candidate)) return
        if (!context.policy.candidate(context.view, task, candidate, context.localAnchor)) return
        if (!context.overwatch && (kotlin.math.abs(candidate.y - entity.y) > 12.0 || candidate.distanceTo(entity.position()) > 48.0)) return
        if (task.opensLane && focus != null && !TacticalRoutes.safeLaneProbe(search.origin, candidate, focus)) return
        val body = entity.getDimensions(entity.pose).makeBoundingBox(candidate)
        if (!safeBody(entity, candidate, body, context.hulls, context.taken)) {
            search.reject("occupied_or_hazard")
            return
        }
        val eye = candidate.add(0.0, entity.eyeHeight.toDouble(), 0.0)
        if (context.overwatch && !protected(entity, task, candidate, context.hulls)) return
        val exposure = context.threats.count {
            !Sightline.blockedBy(level, it.position.add(0.0, 1.5, 0.0), candidate.add(0.0, 1.0, 0.0), entity, context.hulls)
        }
        val mustShoot = task.job in SHOOTING_JOBS
        val firing = focus?.let { !Sightline.blockedBy(level, eye, it.add(0.0, 1.5, 0.0), entity, context.hulls) } ?: true
        if (mustShoot && !firing) {
            search.reject("blocked_shot")
            return
        }
        if (mustShoot && !friendlyLane(entity, eye, focus)) {
            search.reject("friendly_fire")
            return
        }
        search.scored[candidate] = score(context, candidate, exposure, firing, mustShoot)
    }

    private fun score(context: SearchContext, candidate: Vec3, exposure: Int, firing: Boolean, mustShoot: Boolean): Double {
        var score = candidate.distanceTo(context.localAnchor) + candidate.distanceTo(context.entity.position()) * 0.2 + exposure * 5.0
        if (firing && mustShoot) score -= 8.0
        score += context.policy.score(context.view, context.task, candidate, exposure)
        if (context.overwatch) score = DefensiveOverwatch.score(context.task.anchor, context.search.origin, candidate, exposure)
        return score
    }

    /** A budget deferral keeps the current destination; a rejected route advances to the next. */
    private fun findReachablePosition(context: SearchContext): Result {
        val search = context.search
        val entity = context.entity
        val task = context.task
        val destinations = search.destinations ?: search.scored.entries.sortedBy { it.value }.map { it.key }
            .also { search.destinations = it }
        while (search.destinationIndex < destinations.size) {
            if (!TickBudget.hasRaycasts(context.level) || !TacticalBudget.path(context.level.gameTime)) return Result(Outcome.DEFERRED)
            val candidate = destinations[search.destinationIndex]
            val path = pathToCandidate(context, candidate)
            if (reaches(path, candidate)) {
                val safety = checkRoute(context, path!!)
                if (safety == RouteSafety.DEFERRED) return Result(Outcome.DEFERRED)
                if (safety == RouteSafety.SAFE && entity.navigation.moveTo(path, speed(task))) {
                    task.navigationPath = path
                    FiringSpots.claim(entity.uuid, candidate)
                    return finish(task, candidate)
                }
                search.reject(if (safety == RouteSafety.SAFE) "navigation" else "unsafe_route")
            } else {
                search.reject("unreachable_route")
            }
            search.destinationIndex++
        }
        task.search = null
        return Result(Outcome.UNREACHABLE, rejections = search.rejections.toMap())
    }

    private fun pathToCandidate(context: SearchContext, candidate: Vec3): Path? {
        val entity = context.entity
        val body = entity.getDimensions(entity.pose).makeBoundingBox(candidate)
        if (!safeBody(entity, candidate, body, context.hulls, context.taken)) return null
        if (context.overwatch && !protected(entity, context.task, candidate, context.hulls)) return null
        return pathToPosition(entity, candidate)
    }

    private enum class RouteSafety { SAFE, UNSAFE, DEFERRED }

    private fun checkRoute(context: SearchContext, path: Path): RouteSafety {
        val task = context.task
        val focus = context.focus
        for (index in 0 until path.nodeCount) {
            val node = path.getNode(index)
            val foot = Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
            if (!TacticalRules.withinOrder(context.view, foot, context.homeRadius) || GrenadeHazard.threatens(context.level, foot) ||
                !context.policy.route(context.view, foot)
            ) {
                return RouteSafety.UNSAFE
            }
            if (task.opensLane && focus != null && !TacticalRoutes.safeLaneProbe(context.search.origin, foot, focus)) return RouteSafety.UNSAFE
            if (index % 4 == 0 && task.job == TacticalJob.FLANK) {
                if (!TickBudget.hasRaycasts(context.level)) return RouteSafety.DEFERRED
                if (context.threats.any { it.position.distanceTo(foot) < 8.0 }) return RouteSafety.UNSAFE
                if (hasUncoveredApproach(context, foot)) return RouteSafety.UNSAFE
            }
        }
        return RouteSafety.SAFE
    }

    private fun hasUncoveredApproach(context: SearchContext, foot: Vec3): Boolean {
        val threat = context.threats.firstOrNull()
        return !context.covered && threat != null &&
            foot.distanceTo(threat.position) < context.entity.position().distanceTo(threat.position) * 0.6 &&
            !Sightline.blocked(context.level, threat.position.add(0.0, 1.5, 0.0), foot.add(0.0, 1.0, 0.0), context.entity) &&
            !context.policy.permitsExposedFlankAdvance
    }

    internal fun reaches(path: Path?, candidate: Vec3): Boolean =
        path?.canReach() == true && path.endNode?.let {
            Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5).distanceTo(candidate) <= 2.5
        } == true

    fun pathToPosition(entity: NpcEntity, point: Vec3): Path? {
        val navigation = entity.navigation
        return if (navigation is VehicleAwareNavigation) {
            navigation.createPositionPath(BlockPos.containing(point))
        } else {
            navigation.createPath(point.x, point.y, point.z, 0)
        }
    }

    fun speed(task: TacticalTask): Double = if (task.job in RUNNING_JOBS + TacticalJob.OVERWATCH) 1.3 else 1.0

    fun protected(entity: NpcEntity, task: TacticalTask, point: Vec3): Boolean {
        val level = entity.level() as ServerLevel
        return protected(entity, task, point, Sightline.vehicleHulls(level, entity.boundingBox.inflate(64.0), entity, entity.target))
    }

    private fun protected(entity: NpcEntity, task: TacticalTask, point: Vec3, hulls: List<AABB>): Boolean =
        DefensiveOverwatch.protected(point, entity.eyeHeight.toDouble(), task.focus) { from, to ->
            Sightline.blockedBy(entity.level() as ServerLevel, from, to, entity, hulls)
        } && friendlyLane(entity, point.add(0.0, entity.eyeHeight.toDouble(), 0.0), task.focus)

    private fun finish(task: TacticalTask, point: Vec3): Result {
        task.search = null
        return Result(Outcome.FOUND, point)
    }
    private fun safeBody(entity: NpcEntity, point: Vec3, body: AABB, hulls: List<AABB>, taken: List<Vec3>): Boolean =
        entity.level().getFluidState(BlockPos.containing(point)).isEmpty && entity.level().noCollision(entity, body) &&
            hulls.none { it.intersects(body) } && !GrenadeHazard.threatens(entity.level() as ServerLevel, point) && !FiringSpots.crowded(point, taken)
    private fun friendlyLane(entity: NpcEntity, eye: Vec3, focus: Vec3?): Boolean =
        focus == null ||
            FriendlyFireGuard.assess(entity, focus.add(0.0, 1.5, 0.0), 3.0, focus, 0.0, eye).lineClear
    private const val MAX_PROBES_PER_TICK = 24
    private val SHOOTING_JOBS = setOf(TacticalJob.COVER, TacticalJob.ANTI_ARMOUR, TacticalJob.OVERWATCH)
    private val RUNNING_JOBS = TacticalJob.RUNNING
    private val OFFSETS = listOf(
        Vec3.ZERO, Vec3(0.8, 0.0, 0.0), Vec3(-0.8, 0.0, 0.0), Vec3(0.0, 0.0, 0.8), Vec3(0.0, 0.0, -0.8),
        Vec3(4.0, 0.0, 0.0), Vec3(-4.0, 0.0, 0.0), Vec3(0.0, 0.0, 4.0), Vec3(0.0, 0.0, -4.0),
        Vec3(4.0, 0.0, 4.0), Vec3(-4.0, 0.0, 4.0), Vec3(4.0, 0.0, -4.0), Vec3(-4.0, 0.0, -4.0),
    )
}
