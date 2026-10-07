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
    data class Result(val outcome: Outcome, val position: Vec3? = null, val rejections: Map<String, Int> = emptyMap(), val maneuverPost: Boolean = false)

    fun find(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot): Result {
        val level = entity.level() as ServerLevel
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return Result(Outcome.DEFERRED)
        val overwatch = task.job == TacticalJob.OVERWATCH && !task.localFireRecovery
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0 +
            if (task.job == TacticalJob.OVERWATCH) DefensiveOverwatch.RADIUS else 0.0
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
        val deployment: Boolean,
        val taken: List<Vec3>,
        val threats: List<TacticalContact>,
        val hulls: List<AABB>,
        val localAnchor: Vec3,
        val search: TacticalPositionSearch,
        val policy: TacticalPositionPolicy,
        val covered: Boolean,
        val aim: TacticalAim?,
    ) {
        val focus = aim?.position
    }

    private fun existingCoverPosition(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, level: ServerLevel, homeRadius: Double): Vec3? {
        val member = view.members.firstOrNull { it.id == entity.uuid }
        val post = task.localFirePost ?: task.staging ?: task.anchor
        val aim = TacticalAim.resolve(entity, task)
        val canKeepPosition = task.job in TacticalJob.FIRE_SUPPORT && member != null && TacticalEvidence.covers(member, task) &&
            !task.deployingFront(member.position) &&
            TacticalFirePosts.withinPost(post, member.position, task.localFireRecovery) &&
            TacticalRules.withinOrder(view, member.position, homeRadius) && !GrenadeHazard.threatens(level, member.position) &&
            safeDistance(entity, view, member.position) && TickBudget.hasRaycasts(level) &&
            aim?.let { DefensiveOverwatch.nearbyCover(member.position, it.eye) { from, to -> Sightline.blocked(level, from, to, entity) } } == true &&
            firingReady(entity, task, member.position)
        return if (canKeepPosition) member?.position else null
    }

    private fun prepareSearch(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, level: ServerLevel, homeRadius: Double, overwatch: Boolean): SearchContext {
        val taken = FiringSpots.nearbyWithBodies(level, entity, 64.0)
        val aim = TacticalAim.resolve(entity, task)
        val known = view.contacts.filter { view.now - it.seenAt <= 200 }.sortedByDescending { it.priority }
        val targetThreat = aim?.let { TacticalContact(it.target ?: java.util.UUID(0, 0), it.position, view.now) }
        val threats = (listOfNotNull(targetThreat) + known).distinctBy { it.id }.take(3)
        val hulls = Sightline.vehicleHulls(level, entity.boundingBox.inflate(64.0), entity, entity.target)
        val deployment = task.deployingFront(entity.position())
        val localAnchor = if (deployment) {
            task.frontage!!.leg(entity.position())
        } else {
            task.localFirePost ?: task.staging ?: if (task.job in RUNNING_JOBS + TacticalJob.SEARCH) {
                TacticalRoutes.leg(entity.position(), task)
            } else {
                task.anchor
            }
        }
        val oldSearch = task.search
        if (oldSearch?.needsRestart(entity.position(), aim, deployment) == true) task.search = null
        if (overwatch && task.search == null) entity.navigation.stop()
        val search = task.search ?: TacticalPositionSearch(
            entity.position(),
            when {
                overwatch -> DefensiveOverwatch.probes(task.anchor)

                deployment -> OFFSETS.map { localAnchor.add(it) }

                task.frontage != null -> task.frontage!!.probes(recovery = true)

                task.job == TacticalJob.FLANK && localAnchor.subtract(task.anchor).horizontalDistance() < 1.0 ->
                    TacticalFrontPost(task.anchor, (task.focus ?: view.home ?: task.anchor).subtract(view.center).multiply(1.0, 0.0, 1.0).normalize()).probes(recovery = true)

                task.job in TacticalJob.FIRING_POSITIONS -> listOf(entity.position()) + TacticalFirePosts.probes(localAnchor, task.localFireRecovery)

                else -> OFFSETS.map { localAnchor.add(it) }
            },
            aim,
            deployment,
        ).also { task.search = it }
        val plan = entity.currentSquad()?.tactics?.plan
        val policy = plan?.behavior?.positions ?: DefaultTacticalPositions
        val covered = plan != null && view.members.count { member ->
            plan.tasks[member.id]?.job in TacticalJob.FIRE_SUPPORT && TacticalEvidence.firesCover(member, plan.tasks[member.id])
        } >= TacticalFlanks.requiredCover(view)
        return SearchContext(entity, task, view, level, homeRadius, overwatch, deployment, taken, threats, hulls, localAnchor, search, policy, covered, aim)
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
        if (task.job in TacticalJob.FIRING_POSITIONS && !context.overwatch && !TacticalFirePosts.withinPost(context.localAnchor, candidate, task.localFireRecovery)) return
        if (!context.policy.candidate(context.view, task, candidate, context.localAnchor)) return
        val front = task.frontage
        if (front != null) {
            val permitted = if (context.deployment) front.acceptsDeployment(search.origin, candidate, task.localFireRecovery) else front.permits(candidate, recovery = true)
            if (!permitted) {
                search.reject("outside_front_slot")
                return
            }
        }
        if (task.job == TacticalJob.FLANK && context.localAnchor.subtract(task.anchor).horizontalDistance() < 1.0 &&
            candidate.subtract(task.anchor).horizontalDistance() > 14.0
        ) {
            return
        }
        if (!safeDistance(entity, context.view, candidate)) {
            search.reject("close_enemy")
            return
        }
        if (!context.overwatch && (kotlin.math.abs(candidate.y - entity.y) > 12.0 || candidate.distanceTo(entity.position()) > 48.0)) return
        if (task.opensLane && focus != null && !TacticalRoutes.safeLaneProbe(search.origin, candidate, focus, task.probeAdvance)) return
        val body = entity.getDimensions(entity.pose).makeBoundingBox(candidate)
        if (!safeBody(entity, candidate, body, context.hulls, context.taken)) {
            search.reject("occupied_or_hazard")
            return
        }
        val eye = candidate.add(0.0, entity.eyeHeight.toDouble(), 0.0)
        if (context.overwatch && !protected(entity, task, candidate, context.hulls)) return
        val exposure = context.threats.count {
            !Sightline.blockedBy(level, if (it.id == context.aim?.target) context.aim.eye else it.position.add(0.0, 1.5, 0.0), candidate.add(0.0, 0.9, 0.0), entity, context.hulls)
        }
        val mustShoot = requiresFiringLane(context, candidate)
        val aim = context.aim
        val firing = aim?.let {
            !Sightline.blockedBy(level, eye, it.eye, entity, context.hulls) && DetectionSightline.visible(level, eye, it.eye, entity)
        } ?: true
        if (mustShoot && !firing) {
            search.reject("blocked_shot")
            return
        }
        if (mustShoot && !friendlyLane(entity, eye, aim?.eye)) {
            search.reject("friendly_fire")
            return
        }
        val nearbyCover = aim?.let { DefensiveOverwatch.nearbyCover(candidate, it.eye) { from, to -> Sightline.blockedBy(level, from, to, entity, context.hulls) } } == true
        if (!context.deployment && front != null && !front.permitsFiringPost(candidate, nearbyCover, task.localFireRecovery)) {
            search.reject("open_depth_adjustment")
            return
        }
        search.scored[candidate] = score(context, candidate, exposure, firing, mustShoot, nearbyCover)
    }

    private fun score(context: SearchContext, candidate: Vec3, exposure: Int, firing: Boolean, mustShoot: Boolean, nearbyCover: Boolean): TacticalPositionScore {
        if (context.deployment) {
            return TacticalPositionScore(false, 0, candidate.subtract(context.task.frontage!!.point).horizontalDistance())
        }
        var score = candidate.distanceTo(context.localAnchor) + candidate.distanceTo(context.entity.position())
        if (firing && mustShoot) score -= 8.0
        score += context.policy.score(context.view, context.task, candidate, exposure)
        if (context.overwatch) return TacticalPositionScore(false, 0, DefensiveOverwatch.score(context.task.anchor, context.search.origin, candidate, exposure))
        return TacticalPositionScore(!nearbyCover, exposure, score)
    }

    /** A budget deferral keeps the current destination; a rejected route advances to the next. */
    private fun findReachablePosition(context: SearchContext): Result {
        val search = context.search
        val entity = context.entity
        val task = context.task
        val destinations = search.destinations ?: search.scored.entries.sortedBy { it.value }.map { it.key }
            .also { search.destinations = it }
        while (search.destinationIndex < destinations.size) {
            if (!TickBudget.hasRaycasts(context.level)) return Result(Outcome.DEFERRED)
            val candidate = destinations[search.destinationIndex]
            val mustShoot = requiresFiringLane(context, candidate)
            if (mustShoot && !firingReady(entity, task, candidate)) {
                search.reject("changed_firing_lane")
                search.destinationIndex++
                continue
            }
            if (FiringPostArrival.reached(entity.position(), candidate) && safeDistance(entity, context.view, candidate)) {
                entity.navigation.stop()
                task.navigationPath = null
                FiringSpots.claim(entity.uuid, entity.position())
                tracePosition(context, entity.position(), search.scored[candidate])
                return finish(task, entity.position(), allowDepthAdjustment = !context.deployment, maneuverPost = task.job == TacticalJob.FLANK && mustShoot)
            }
            if (!TacticalBudget.path(context.level.gameTime)) return Result(Outcome.DEFERRED)
            val path = pathToCandidate(context, candidate)
            if (reaches(path, candidate)) {
                val safety = checkRoute(context, path!!)
                if (safety == RouteSafety.DEFERRED) return Result(Outcome.DEFERRED)
                if (safety == RouteSafety.SAFE && entity.navigation.moveTo(path, speed(task))) {
                    task.navigationPath = path
                    FiringSpots.claim(entity.uuid, candidate)
                    tracePosition(context, candidate, search.scored[candidate])
                    return finish(task, candidate, allowDepthAdjustment = !context.deployment, maneuverPost = task.job == TacticalJob.FLANK && mustShoot)
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

    private fun tracePosition(context: SearchContext, point: Vec3, quality: TacticalPositionScore?) {
        if (!DebugFlags.on(LogGroup.ORDER)) return
        DebugFlags.log(
            LogGroup.ORDER, "[tactics] {} squad={} tick={} plan={} event=position_selected job={} target={} position={} quality={} nearestEnemy={} rejected={} frontSlot={} deploying={} recovery={}",
            context.entity.uuid, context.entity.currentSquad()?.id, context.level.gameTime, context.task.plan, context.task.job, context.aim?.target, point, quality,
            TacticalClearance.threats(context.view).minOfOrNull { point.subtract(it).horizontalDistance() }, context.search.rejections,
            context.task.frontage?.point, context.deployment, context.task.localFireRecovery,
        )
    }

    private fun requiresFiringLane(context: SearchContext, point: Vec3): Boolean =
        requiresFiringLane(context.task, context.deployment, point) ||
            (context.task.job == TacticalJob.FLANK && context.localAnchor.subtract(context.task.anchor).horizontalDistance() < 1.0)

    /** Reaching the line is movement. The subsequent firing-post query checks the shot. */
    internal fun requiresFiringLane(task: TacticalTask, deploying: Boolean, point: Vec3): Boolean =
        (task.job in SHOOTING_JOBS && !deploying) ||
            (task.job == TacticalJob.FLANK && point.distanceTo(task.anchor) <= 10.0)

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
        if (!safePath(context.entity, context.view, path)) return RouteSafety.UNSAFE
        for (index in path.nextNodeIndex until path.nodeCount) {
            val node = path.getNode(index)
            val foot = Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
            if (!TacticalRules.withinOrder(context.view, foot, context.homeRadius) || GrenadeHazard.threatens(context.level, foot) ||
                !context.policy.route(context.view, foot)
            ) {
                return RouteSafety.UNSAFE
            }
            if (task.opensLane && focus != null && !TacticalRoutes.safeLaneProbe(context.search.origin, foot, focus, task.probeAdvance)) return RouteSafety.UNSAFE
            if (index % 4 == 0 && task.job == TacticalJob.FLANK) {
                if (!TickBudget.hasRaycasts(context.level)) return RouteSafety.DEFERRED
                if (hasUncoveredApproach(context, foot)) return RouteSafety.UNSAFE
            }
        }
        return RouteSafety.SAFE
    }

    fun safeDistance(entity: NpcEntity, view: TacticalSnapshot, point: Vec3): Boolean = TacticalClearance.permitsPosition(entity.npcClass, entity.position(), point, view)

    /** Repaths and already-running paths must respect newly reported or approaching enemies too. */
    fun safePath(entity: NpcEntity, view: TacticalSnapshot, path: Path): Boolean = TacticalClearance.permitsPath(entity.npcClass, entity.position(), path, view)

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
        DefensiveOverwatch.protected(point, entity.eyeHeight.toDouble(), TacticalAim.resolve(entity, task)?.position) { from, to ->
            Sightline.blockedBy(entity.level() as ServerLevel, from, to, entity, hulls)
        } && friendlyLane(entity, point.add(0.0, entity.eyeHeight.toDouble(), 0.0), TacticalAim.resolve(entity, task)?.eye)

    private fun finish(task: TacticalTask, point: Vec3, allowDepthAdjustment: Boolean = false, maneuverPost: Boolean = false): Result {
        if (allowDepthAdjustment && task.frontage?.contains(point) == false) task.localFirePost = task.frontage!!.point
        task.search = null
        return Result(Outcome.FOUND, point, maneuverPost = maneuverPost)
    }
    private fun safeBody(entity: NpcEntity, point: Vec3, body: AABB, hulls: List<AABB>, taken: List<Vec3>): Boolean =
        entity.level().getFluidState(BlockPos.containing(point)).isEmpty && entity.level().noCollision(entity, body) &&
            hulls.none { it.intersects(body) } && !GrenadeHazard.threatens(entity.level() as ServerLevel, point) && !FiringSpots.crowded(point, taken)
    private fun friendlyLane(entity: NpcEntity, eye: Vec3, targetEye: Vec3?): Boolean = targetEye == null || FriendlyFireGuard.assess(entity, targetEye, entity.spread, targetEye, 0.0, eye).lineClear

    fun firingReady(entity: NpcEntity, task: TacticalTask, point: Vec3): Boolean {
        val aim = TacticalAim.resolve(entity, task) ?: return true
        val eye = point.add(0.0, entity.eyeHeight.toDouble(), 0.0)
        val level = entity.level() as ServerLevel
        val hulls = Sightline.vehicleHulls(level, AABB(eye, aim.eye).inflate(2.0), entity, entity.target)
        return !Sightline.blockedBy(level, eye, aim.eye, entity, hulls, entity.spread) &&
            DetectionSightline.visible(level, eye, aim.eye, entity) && friendlyLane(entity, eye, aim.eye)
    }
    private const val MAX_PROBES_PER_TICK = 24
    private val SHOOTING_JOBS = TacticalJob.FIRING_POSITIONS
    private val RUNNING_JOBS = TacticalJob.RUNNING
    private val OFFSETS = listOf(
        Vec3.ZERO, Vec3(0.8, 0.0, 0.0), Vec3(-0.8, 0.0, 0.0), Vec3(0.0, 0.0, 0.8), Vec3(0.0, 0.0, -0.8),
        Vec3(4.0, 0.0, 0.0), Vec3(-4.0, 0.0, 0.0), Vec3(0.0, 0.0, 4.0), Vec3(0.0, 0.0, -4.0),
        Vec3(4.0, 0.0, 4.0), Vec3(-4.0, 0.0, 4.0), Vec3(4.0, 0.0, -4.0), Vec3(-4.0, 0.0, -4.0),
    )
}
