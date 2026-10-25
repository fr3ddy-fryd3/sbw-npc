package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.combat.WeaponReach
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcPosture
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext

/** Local positions are validated through the existing navigation and collider raycasts. */
object TacticalPositions {
    enum class Outcome { FOUND, DEFERRED, UNREACHABLE }
    data class Result(val outcome: Outcome, val position: Vec3? = null, val rejections: Map<String, Int> = emptyMap(), val coarseExhausted: Boolean = false)

    /** Optional work counter; disabled during ordinary gameplay. True marks a fresh cursor. */
    internal var searchWorkObserver: ((NpcEntity, Boolean) -> Unit)? = null

    data class Query(val policy: TacticalPositionPolicy, val claimOwner: java.util.UUID? = null)

    fun find(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, query: Query = Query(DefaultTacticalPositions)): Result {
        searchWorkObserver?.invoke(entity, false)
        val search = task.search
        val pending = search?.pendingPath
        if (pending != null) {
            // Native A* admission is independent of the raycast queue. A request that only
            // reappears on its next ray turn can expire and block every successor meanwhile.
            if ((entity.navigation as? PositionPaths)?.canPlan == false) return Result(Outcome.DEFERRED)
            val overwatch = task.job == TacticalJob.OVERWATCH && !task.localFireRecovery
            if (search.needsRestart(entity.position(), if (overwatch) null else TacticalAim.resolve(entity, task))) {
                task.search = null
            } else {
                if (!TacticalBudget.path(entity)) return Result(Outcome.DEFERRED)
                val path = pathToCandidates(entity, pending, task.expandedFireSearch)
                recordPath(entity, task, search, pending, path)
                // Recheck bodies, aim, cover and every route node in the normal ray turn.
                // A queued proposal never directly commits navigation or skips live safety.
                return Result(Outcome.DEFERRED)
            }
        }
        return TickBudget.search(entity, TickBudget.Work.POSITIONS, Result(Outcome.DEFERRED)) { findInTurn(entity, task, view, query) }
    }

    private fun recordPath(entity: NpcEntity, task: TacticalTask, search: TacticalPositionSearch, batch: List<Vec3>, path: Path?) {
        search.pendingPath = null
        search.pathAttempts++
        val selected = path?.takeIf { it.canReach() }?.let { route -> batch.firstOrNull { PositionPaths.walkingNode(it) == route.target } }
        if (selected != null) {
            search.routeValidation = TacticalRouteValidation(path!!, entity.level().gameTime, selected)
        } else {
            search.rejectedRoutes.addAll(batch)
            DebugFlags.log(LogGroup.PATH, "[tactics] {} plan={} event=post_route_batch_rejected candidates={} attempts={} end={}", entity.uuid, task.plan, batch.size, search.pathAttempts, path?.endNode?.asBlockPos())
        }
    }

    private fun findInTurn(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, query: Query): Result {
        val level = entity.level() as ServerLevel
        if ((entity.navigation as? PositionPaths)?.canPlan == false) return Result(Outcome.DEFERRED)
        val overwatch = task.job == TacticalJob.OVERWATCH && !task.localFireRecovery
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0 +
            if (task.job == TacticalJob.OVERWATCH) DefensiveOverwatch.RADIUS else 0.0
        val heldPosition = existingCoverPosition(entity, task, view, level, homeRadius, query.policy.clearance(entity.npcClass))
        if (heldPosition != null) {
            DebugFlags.log(LogGroup.ORDER, "[tactics] {} plan={} event=position_kept position={} reason=usable_nearby_cover", entity.uuid, task.plan, heldPosition)
            return finish(task, heldPosition)
        }
        if (!TickBudget.hasRaycasts(level)) return Result(Outcome.DEFERRED)

        val context = prepareSearch(entity, task, view, level, homeRadius, overwatch, query)
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
        val aim: TacticalAim?,
        val claimOwner: java.util.UUID?,
    )

    private fun existingCoverPosition(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, level: ServerLevel, homeRadius: Double, clearance: Double): Vec3? {
        if (task.job !in TacticalJob.FIRE_SUPPORT) return null
        val member = view.members.firstOrNull { it.id == entity.uuid } ?: return null
        if (!TacticalEvidence.covers(member, task)) return null
        val point = member.position
        val post = task.localFirePost ?: task.anchor
        if (!TacticalFirePosts.withinPost(post, point, task.localFireRecovery)) return null
        if (task.expandedFireSearch && FiringSpots.crowded(point, FiringSpots.nearbyWithBodies(level, entity, 64.0))) return null
        if (!TacticalRules.withinOrder(view, point, homeRadius) || GrenadeHazard.threatens(level, point)) return null
        if (!safeDistance(entity, view, point, clearance) || !TickBudget.hasRaycasts(level)) return null
        val aim = TacticalAim.resolve(entity, task) ?: return null
        val sheltered = DefensiveOverwatch.nearbyCover(point, aim.eye) { from, to -> Sightline.blocked(level, from, to, entity) }
        return point.takeIf { sheltered && firingReady(entity, task, point) }
    }

    private fun prepareSearch(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, level: ServerLevel, homeRadius: Double, overwatch: Boolean, query: Query): SearchContext {
        val taken = FiringSpots.nearbyWithBodies(level, entity, 64.0)
        val aim = TacticalAim.resolve(entity, task)
        val known = view.contacts.filter { view.now - it.seenAt <= 200 }.sortedByDescending { it.priority }
        val targetThreat = aim?.let { TacticalContact(it.target ?: java.util.UUID(0, 0), it.position, view.now) }
        val threats = (listOfNotNull(targetThreat) + known).distinctBy { it.id }.take(3)
        val hulls = Sightline.vehicleHulls(level, entity.boundingBox.inflate(64.0), entity, entity.target)
        val localAnchor = task.localFirePost ?: if (task.job in TacticalRoutes.TRANSIT_JOBS) {
            TacticalRoutes.routeLeg(entity, task)
        } else {
            task.anchor
        }
        val oldSearch = task.search
        val policy = query.policy
        // Terrain and protection define a sentry post. Switching reported targets must not
        // restart its multi-tick height scan before it ever reaches the ladder.
        val searchAim = if (overwatch) null else aim
        if (oldSearch?.needsRestart(entity.position(), searchAim) == true) task.search = null
        if (overwatch && task.search == null) entity.navigation.stop()
        if (task.search == null) searchWorkObserver?.invoke(entity, true)
        val search = task.search ?: TacticalPositionSearch(
            entity.position(),
            (
                when {
                    overwatch -> DefensiveOverwatch.probes(task.anchor)
                    task.expandedFireSearch -> (TacticalFirePosts.probes(entity.position()) + TacticalFirePosts.probes(localAnchor, recovery = true)).distinct()
                    task.job in TacticalJob.FIRING_POSITIONS -> listOf(entity.position()) + TacticalFirePosts.probes(localAnchor, task.localFireRecovery)
                    else -> OFFSETS.map { localAnchor.add(it) }
                } + policy.additionalProbes(view, task, entity.position(), entity.npcClass)
                ).distinct(),
            searchAim,
        ).also { task.search = it }
        return SearchContext(entity, task, view, level, homeRadius, overwatch, taken, threats, hulls, localAnchor, search, policy, aim, query.claimOwner)
    }

    /** The probe cursor advances before validation, including rejected candidates. */
    private fun collectCandidates(context: SearchContext): Boolean {
        val search = context.search
        if (search.destinations != null) return true
        var probesThisTick = 0
        while (search.probeIndex < search.probes.size) {
            if (!TickBudget.hasRaycasts(context.level) || probesThisTick++ >= MAX_PROBES_PER_TICK) {
                return search.readyBatch(context.overwatch) || context.policy.readyBatch(context.task, context.localAnchor, search.scored)
            }
            val raw = search.probes[search.probeIndex++]
            scoreCandidate(context, raw)
            if (search.denseRecovery && search.scored.size >= DENSE_CANDIDATE_BATCH) return true
        }
        return true
    }

    private fun scoreCandidate(context: SearchContext, raw: Vec3) {
        val entity = context.entity
        val task = context.task
        val level = context.level
        val search = context.search
        val block = BlockPos.containing(raw)
        if (!level.chunkSource.hasChunk(block.x shr 4, block.z shr 4)) {
            search.reject("unloaded")
            return
        }
        val candidate = Terrain.feetAt(level, raw, if (context.overwatch) 9 else 12) {
            if (!level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(it))) {
                false
            } else if (context.overwatch && onLeaves(level, it)) {
                // A canopy is no post, and through it the column finds the floor below. Counted,
                // every treetop of a forest was an exposed raised floor whose neighbourhood was
                // refined for a parapet: 9800 probes, 20 s before a sentry took a built platform
                // (in-game 24.10), and his first routes went to the unreachable crowns.
                search.reject("leaves")
                false
            } else if (context.overwatch && !search.floorProtection.getOrPut(it) { protected(entity, task, it, context.hulls) }) {
                // An exposed roof must not hide a sheltered, walkable floor below it.
                if (it.y > task.anchor.y + 2.0) search.refineOverwatch(task.anchor, it)
                search.reject("unprotected_overwatch_floor")
                false
            } else {
                true
            }
        }
        if (candidate == null) {
            search.reject("terrain")
            return
        }
        if (!search.visited.add(candidate)) return
        if (!TacticalGoalProgress.permitsAdvance(task, search.origin, candidate)) {
            search.reject("no_goal_progress")
            return
        }
        if (!TacticalRules.withinOrder(context.view, candidate, context.homeRadius)) {
            search.reject("order")
            return
        }
        if (context.overwatch && !DefensiveOverwatch.within(task.anchor, candidate)) return
        if (task.job in TacticalJob.FIRING_POSITIONS && !context.overwatch &&
            !TacticalFirePosts.withinPost(context.localAnchor, candidate, task.localFireRecovery)
        ) {
            return
        }
        if (!context.policy.candidate(context.view, task, candidate, context.localAnchor)) return
        if (!safeDistance(entity, context.view, candidate, context.policy.clearance(entity.npcClass))) {
            search.reject("close_enemy")
            return
        }
        if (!context.overwatch && (kotlin.math.abs(candidate.y - entity.y) > 12.0 || candidate.distanceTo(entity.position()) > SEARCH_REACH)) return
        if (onClimbingShaft(level, candidate)) {
            search.reject("climbing_shaft")
            return
        }
        val body = entity.getDimensions(entity.pose).makeBoundingBox(candidate)
        if (!safeBody(entity, candidate, body, context.hulls, context.taken)) {
            search.reject("occupied_or_hazard")
            return
        }
        val eye = candidate.add(0.0, entity.eyeHeight.toDouble(), 0.0)
        val exposure = context.threats.count {
            !Sightline.blockedBy(level, if (it.id == context.aim?.target) context.aim.eye else it.position.add(0.0, 1.5, 0.0), candidate.add(0.0, 0.9, 0.0), entity, context.hulls)
        }
        val mustShoot = requiresFiringLane(context, candidate)
        val aim = context.aim
        if (context.view.offensive &&
            !entity.usingSidearm && task.job in TacticalJob.FIRE_SUPPORT &&
            aim != null && !WeaponReach.inRange(entity, eye, aim.eye)
        ) {
            search.reject("weapon_range")
            return
        }
        val firing = aim?.let {
            !Sightline.blockedBy(level, eye, it.eye, entity, context.hulls) && clearOfGrass(level, entity, candidate, it.eye)
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
        if (context.policy.requiresShelter(context.view, task) && !nearbyCover) {
            search.reject("exposed_advance_under_fire")
            return
        }
        search.scored[candidate] = score(context, candidate, exposure, firing, mustShoot, nearbyCover)
    }

    /**
     * Whether a man kneeling at [candidate] sees out of the grass round him toward [aim].
     *
     * [Sightline] goes by collision and looks straight through grass; spotting
     * ([DetectionSightline]) is hidden by three blocks of it. Posts in a meadow passed the firing
     * check, and the men knelt in them seeing nobody (#159). Only the first [GRASS_REACH] blocks
     * are read: grass by the target hides him from every post alike and is no reason to pass this
     * one over. Kneeling, since [NpcPosture] puts him down once he is settled there.
     */
    private fun clearOfGrass(level: ServerLevel, entity: NpcEntity, candidate: Vec3, aim: Vec3): Boolean {
        val eye = candidate.add(0.0, NpcPosture.CROUCHING_DIMENSIONS.eyeHeight().toDouble(), 0.0)
        val toward = aim.subtract(eye)
        val end = eye.add(toward.scale(minOf(1.0, GRASS_REACH / toward.length())))
        return DetectionSightline.trace(level, eye, end, CollisionContext.of(entity)) != DetectionSightline.Visibility.FOLIAGE
    }

    private fun score(context: SearchContext, candidate: Vec3, exposure: Int, firing: Boolean, mustShoot: Boolean, nearbyCover: Boolean): TacticalPositionScore {
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
        search.skipRejectedRoutes()
        while (search.destinationIndex < destinations.size) {
            if (!TickBudget.hasRaycasts(context.level)) return Result(Outcome.DEFERRED)
            if (search.routeValidation?.fresh(context.level.gameTime) == false) search.routeValidation = null
            var candidate = search.routeValidation?.candidate ?: destinations[search.destinationIndex]
            val mustShoot = requiresFiringLane(context, candidate)
            if (mustShoot && !firingReady(entity, task, candidate)) {
                search.routeValidation = null
                search.reject("changed_firing_lane")
                search.rejectedRoutes += candidate
                search.skipRejectedRoutes()
                continue
            }
            val body = entity.getDimensions(entity.pose).makeBoundingBox(candidate)
            if (!safeBody(entity, candidate, body, context.hulls, context.taken)) {
                search.routeValidation = null
                search.reject("occupied_on_review")
                search.rejectedRoutes += candidate
                search.skipRejectedRoutes()
                continue
            }
            if (context.policy.requiresShelter(context.view, task) && context.aim?.let { aim ->
                    DefensiveOverwatch.nearbyCover(candidate, aim.eye) { from, to -> Sightline.blockedBy(context.level, from, to, entity, context.hulls) }
                } != true
            ) {
                // Incoming fire can begin while the scored batch waits for native A*.
                // Never commit an old open-ground proposal as a protected advance.
                search.routeValidation = null
                search.reject("changed_advance_cover")
                search.rejectedRoutes += candidate
                search.skipRejectedRoutes()
                continue
            }
            if (search.routeValidation != null && context.overwatch && !protected(entity, task, candidate, context.hulls)) {
                search.routeValidation = null
                search.reject("changed_overwatch_cover")
                search.rejectedRoutes += candidate
                search.skipRejectedRoutes()
                continue
            }
            if (FiringPostArrival.reached(entity.position(), candidate) && safeDistance(entity, context.view, candidate, context.policy.clearance(entity.npcClass))) {
                entity.navigation.stop()
                task.navigationPath = null
                FiringSpots.claim(entity.uuid, entity.position(), context.claimOwner)
                tracePosition(context, entity.position(), search.scored[candidate])
                return finish(task, entity.position())
            }
            if (search.routeValidation == null) {
                val batch = routeBatch(context, candidate, destinations)
                if (batch.isEmpty()) return Result(Outcome.DEFERRED)
                if (!TacticalBudget.path(entity)) {
                    search.pendingPath = batch
                    return Result(Outcome.DEFERRED)
                }
                val path = pathToCandidates(entity, batch, task.expandedFireSearch)
                recordPath(entity, task, search, batch, path)
                candidate = search.routeValidation?.candidate ?: candidate
            }
            val validation = search.routeValidation
            val path = validation?.path
            if (reaches(path, candidate)) {
                val safe = safeRoute(context, path!!)
                if (safe && entity.navigation.moveTo(path, speed(task, entity.position()))) {
                    task.navigationPath = path
                    FiringSpots.claim(entity.uuid, candidate, context.claimOwner)
                    tracePosition(context, candidate, search.scored[candidate])
                    return finish(task, candidate)
                }
                search.reject(if (safe) "navigation" else "unsafe_route")
            } else {
                search.reject("unreachable_route")
                DebugFlags.log(
                    LogGroup.PATH,
                    "[tactics] {} squad={} tick={} plan={} event=post_route_rejected from={} candidate={} reachable={} end={} expanded={}",
                    entity.uuid, entity.currentSquad()?.id, context.level.gameTime, task.plan, entity.position(), candidate,
                    path?.canReach(), path?.endNode?.asBlockPos(), task.expandedFireSearch,
                )
            }
            search.routeValidation = null
            search.rejectedRoutes += candidate
            search.skipRejectedRoutes()
        }
        if (search.probeIndex < search.probes.size) {
            search.clearCandidates()
            return Result(Outcome.DEFERRED)
        }
        val coarseExhausted = search.scored.isEmpty()
        if (task.expandedFireSearch && search.extendRecovery(task.localFirePost!!)) {
            DebugFlags.log(
                LogGroup.ORDER,
                "[tactics] {} plan={} event=fire_post_dense_search origin={} radius={} probes={}",
                entity.uuid,
                task.plan,
                task.localFirePost,
                TacticalFirePosts.RECOVERY_RADIUS,
                search.probes.size,
            )
            return Result(Outcome.DEFERRED, coarseExhausted = coarseExhausted)
        }
        task.search = null
        return Result(Outcome.UNREACHABLE, rejections = search.rejections.toMap())
    }

    private fun tracePosition(context: SearchContext, point: Vec3, quality: TacticalPositionScore?) {
        if (!DebugFlags.on(LogGroup.ORDER)) return
        DebugFlags.log(
            LogGroup.ORDER, "[tactics] {} squad={} tick={} plan={} event=position_selected job={} target={} position={} quality={} nearestEnemy={} rejected={} recovery={} from={} shotRequired={}",
            context.entity.uuid, context.entity.currentSquad()?.id, context.level.gameTime, context.task.plan, context.task.job, context.aim?.target, point, quality,
            TacticalClearance.threats(context.view).minOfOrNull { point.subtract(it).horizontalDistance() }, context.search.rejections,
            context.task.localFireRecovery, context.search.origin, requiresFiringLane(context, point),
        )
    }

    private fun requiresFiringLane(context: SearchContext, point: Vec3): Boolean =
        (context.aim == null || WeaponReach.inRange(context.entity, point.add(0.0, context.entity.eyeHeight.toDouble(), 0.0), context.aim.eye)) &&
            requiresFiringLane(context.task)

    /** A transit leg may be blind; a firing post must already allow a shot. */
    internal fun requiresFiringLane(task: TacticalTask): Boolean = task.job in SHOOTING_JOBS && task.job != TacticalJob.OVERWATCH

    /** Preserve shelter/exposure tiers, and the height tier of a defensive overwatch post. */
    private fun routeBatch(context: SearchContext, first: Vec3, destinations: List<Vec3>): List<Vec3> {
        val result = ArrayList<Vec3>()
        val firstScore = context.search.scored[first]
        for (index in context.search.destinationIndex until destinations.size) {
            val point = destinations[index]
            if (point in context.search.rejectedRoutes) continue
            val score = context.search.scored[point]
            if (firstScore == null || score == null || !firstScore.sameRouteTier(score) ||
                (context.overwatch && kotlin.math.abs(point.y - first.y) > 0.01)
            ) {
                break
            }
            if (!TickBudget.hasRaycasts(context.level)) break
            val body = context.entity.getDimensions(context.entity.pose).makeBoundingBox(point)
            val valid = safeBody(context.entity, point, body, context.hulls, context.taken) &&
                (!requiresFiringLane(context, point) || firingReady(context.entity, context.task, point)) &&
                (!context.overwatch || protected(context.entity, context.task, point, context.hulls))
            if (valid) {
                result += point
                if (result.size == 16) break
            } else {
                context.search.rejectedRoutes += point
                context.search.reject("changed_batch_post")
            }
        }
        return result
    }

    private fun onLeaves(level: Level, feet: Vec3): Boolean {
        val state = level.getBlockState(BlockPos.containing(feet.x, feet.y - FLOOR_DEPTH, feet.z))
        return state.block is LeavesBlock || state.`is`(BlockTags.LEAVES)
    }

    fun pathToCandidates(entity: NpcEntity, points: List<Vec3>, allowDetour: Boolean = false): Path? =
        TacticalBudget.measure(entity) {
            val targets = points.mapTo(LinkedHashSet()) { PositionPaths.walkingNode(it) }
            val navigation = entity.navigation
            if (navigation is PositionPaths) navigation.createPositionPath(targets, allowDetour) else navigation.createPath(targets, 0)
        }

    private fun safeRoute(context: SearchContext, path: Path): Boolean {
        if (!safePath(context.entity, context.view, path, context.policy.clearance(context.entity.npcClass))) return false
        return (path.nextNodeIndex until path.nodeCount).none { index ->
            val node = path.getNode(index)
            val foot = Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
            !TacticalRules.withinOrder(context.view, foot, context.homeRadius) || GrenadeHazard.threatens(context.level, foot) ||
                !context.policy.route(context.view, context.task, context.search.origin, foot)
        }
    }

    fun safeDistance(entity: NpcEntity, view: TacticalSnapshot, point: Vec3, clearance: Double = CombatPositionSafety.clearance(entity.npcClass)): Boolean = TacticalClearance.permitsPosition(clearance, entity.position(), point, view)

    /** Repaths and already-running paths must respect newly reported or approaching enemies too. */
    fun safePath(entity: NpcEntity, view: TacticalSnapshot, path: Path, clearance: Double = CombatPositionSafety.clearance(entity.npcClass)): Boolean = TacticalClearance.permitsPath(clearance, entity.position(), path, view)

    internal fun reaches(path: Path?, candidate: Vec3): Boolean =
        path?.canReach() == true && path.endNode?.let {
            Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5).distanceTo(candidate) <= 2.5
        } == true

    fun pathToPosition(entity: NpcEntity, point: Vec3): Path? {
        val navigation = entity.navigation
        return TacticalBudget.measure(entity) {
            if (navigation is PositionPaths) {
                navigation.createPositionPath(setOf(PositionPaths.walkingNode(point)))
            } else {
                navigation.createPath(point.x, point.y, point.z, 0)
            }
        }
    }

    fun speed(task: TacticalTask, from: Vec3): Double =
        if (task.job in RUNNING_JOBS + setOf(TacticalJob.OVERWATCH, TacticalJob.SEARCH) || task.deployingFront(from) ||
            (task.job in setOf(TacticalJob.RESERVE, TacticalJob.OBSERVE) && !TacticalGoalProgress.reached(task, from))
        ) {
            1.3
        } else {
            1.0
        }

    fun protected(entity: NpcEntity, task: TacticalTask, point: Vec3): Boolean {
        val level = entity.level() as ServerLevel
        return protected(entity, task, point, Sightline.vehicleHulls(level, entity.boundingBox.inflate(64.0), entity, entity.target))
    }

    /** A held sentry post needs physical shelter, not a clear shot at every new reported target. */
    fun protectedHeldOverwatch(entity: NpcEntity, point: Vec3): Boolean {
        val level = entity.level() as ServerLevel
        return level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(point)) &&
            !GrenadeHazard.threatens(level, point) &&
            DefensiveOverwatch.protected(point, entity.eyeHeight.toDouble(), null) { from, to -> Sightline.blocked(level, from, to, entity) }
    }

    private fun protected(entity: NpcEntity, task: TacticalTask, point: Vec3, hulls: List<AABB>): Boolean {
        val blocked = { from: Vec3, to: Vec3 -> Sightline.blockedBy(entity.level() as ServerLevel, from, to, entity, hulls) }
        if (DefensiveOverwatch.protected(point, entity.eyeHeight.toDouble(), null, blocked)) return true
        // Without an aim the second check would cast the same rays again and fail again; on an
        // open-ground scan that doubled the cost of every rejected floor.
        val aim = TacticalAim.resolve(entity, task) ?: return false
        return DefensiveOverwatch.protected(point, entity.eyeHeight.toDouble(), aim.position, blocked) &&
            friendlyLane(entity, point.add(0.0, entity.eyeHeight.toDouble(), 0.0), aim.eye)
    }

    private fun finish(task: TacticalTask, point: Vec3): Result {
        task.search = null
        return Result(Outcome.FOUND, point)
    }

    /**
     * Whether [point] stands on the top of a scaffolding column, the shaft a tower is climbed through.
     *
     * Unlike a ladder, the top scaffold has a solid deck, so [Terrain.feetAt] accepts it as floor.
     * A post there blocks the shaft for the rest of the squad, and the climber keeps bobbing inside
     * the top scaffold instead of stepping onto it, so the post is never reached.
     */
    private fun onClimbingShaft(level: Level, point: Vec3): Boolean {
        val deck = BlockPos.containing(point).below()
        return level.getBlockState(deck).`is`(Blocks.SCAFFOLDING) && level.getBlockState(deck.below()).`is`(Blocks.SCAFFOLDING)
    }

    private fun safeBody(entity: NpcEntity, point: Vec3, body: AABB, hulls: List<AABB>, taken: List<Vec3>): Boolean =
        entity.level().getFluidState(BlockPos.containing(point)).isEmpty && entity.level().noCollision(entity, body) &&
            hulls.none { it.intersects(body) } && !GrenadeHazard.threatens(entity.level() as ServerLevel, point) && !FiringSpots.crowded(point, taken)
    private fun friendlyLane(entity: NpcEntity, eye: Vec3, targetEye: Vec3?): Boolean = targetEye == null || FriendlyFireGuard.assess(entity, SquadTactics.firingLaneEnd(entity, targetEye, eye), entity.spread, targetEye, 0.0, eye).lineClear

    /** Physical post suitability only; personal visibility must still authorize aimed fire at the weapon. */
    fun firingReady(entity: NpcEntity, task: TacticalTask, point: Vec3): Boolean {
        val aim = TacticalAim.resolve(entity, task) ?: return true
        val eye = point.add(0.0, entity.eyeHeight.toDouble(), 0.0)
        if (!WeaponReach.inRange(entity, eye, aim.eye)) {
            // A defensive pistol post can wait. An attacking primary gun needs an actual
            // firing post; otherwise shotgunners repeatedly accept unreachable-range posts.
            return entity.currentSquad()?.order != com.sbwnpc.squad.squad.SquadOrder.ATTACK ||
                entity.usingSidearm || task.job !in TacticalJob.FIRE_SUPPORT
        }
        val level = entity.level() as ServerLevel
        val hulls = Sightline.vehicleHulls(level, AABB(eye, aim.eye).inflate(2.0), entity, entity.target)
        return !Sightline.blockedBy(level, eye, aim.eye, entity, hulls, entity.spread) &&
            !Sightline.ownHullInLane(entity, eye, aim.eye, entity.spread, entity.target) &&
            clearOfGrass(level, entity, point, aim.eye) &&
            friendlyLane(entity, eye, aim.eye)
    }

    /** Candidates farther from the searching NPC are skipped; a distant post needs an approach first. */
    const val SEARCH_REACH = 48.0
    private const val MAX_PROBES_PER_TICK = 24

    /** Just under the feet, inside whatever block they stand on, full or partial. */
    private const val FLOOR_DEPTH = 0.01
    private const val DENSE_CANDIDATE_BATCH = 16

    /** How far out from a post the grass round it is looked at, see [clearOfGrass]. */
    private const val GRASS_REACH = 8.0
    private val SHOOTING_JOBS = TacticalJob.FIRING_POSITIONS
    private val RUNNING_JOBS = TacticalJob.RUNNING
    private val OFFSETS = listOf(
        Vec3.ZERO, Vec3(0.8, 0.0, 0.0), Vec3(-0.8, 0.0, 0.0), Vec3(0.0, 0.0, 0.8), Vec3(0.0, 0.0, -0.8),
        Vec3(4.0, 0.0, 0.0), Vec3(-4.0, 0.0, 0.0), Vec3(0.0, 0.0, 4.0), Vec3(0.0, 0.0, -4.0),
        Vec3(4.0, 0.0, 4.0), Vec3(-4.0, 0.0, 4.0), Vec3(4.0, 0.0, -4.0), Vec3(-4.0, 0.0, -4.0),
    )
}
