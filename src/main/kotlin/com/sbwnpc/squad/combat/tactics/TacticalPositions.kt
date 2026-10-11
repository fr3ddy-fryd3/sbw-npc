package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.combat.WeaponReach
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
    data class Result(val outcome: Outcome, val position: Vec3? = null, val rejections: Map<String, Int> = emptyMap(), val maneuverPost: Boolean = false, val coarseExhausted: Boolean = false)

    /** Optional work counter; disabled during ordinary gameplay. True marks a fresh cursor. */
    internal var searchWorkObserver: ((NpcEntity, Boolean) -> Unit)? = null

    fun find(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot): Result {
        searchWorkObserver?.invoke(entity, false)
        val search = task.search
        val pending = search?.pendingPath
        if (pending != null) {
            // Native A* admission is independent of the raycast queue. A request that only
            // reappears on its next ray turn can expire and block every successor meanwhile.
            if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return Result(Outcome.DEFERRED)
            val overwatch = task.job == TacticalJob.OVERWATCH && !task.localFireRecovery
            val deployment = !task.expandedFireSearch && task.deployingFront(entity.position())
            if (search.needsRestart(entity.position(), if (overwatch) null else TacticalAim.resolve(entity, task), deployment)) {
                task.search = null
            } else {
                if (!TacticalBudget.path(entity)) return Result(Outcome.DEFERRED)
                val path = pathToCandidates(entity, pending)
                recordPath(entity, task, search, pending, path)
                // Recheck bodies, aim, cover and every route node in the normal ray turn.
                // A queued proposal never directly commits navigation or skips live safety.
                return Result(Outcome.DEFERRED)
            }
        }
        return TickBudget.search(entity, TickBudget.Work.POSITIONS, Result(Outcome.DEFERRED)) { findInTurn(entity, task, view) }
    }

    private fun recordPath(entity: NpcEntity, task: TacticalTask, search: TacticalPositionSearch, batch: List<Vec3>, path: Path?) {
        search.pendingPath = null
        search.pathAttempts++
        val selected = path?.takeIf { it.canReach() }?.let { route -> batch.firstOrNull { VehicleAwareNavigation.walkingNode(it) == route.target } }
        if (selected != null) {
            search.routeValidation = TacticalRouteValidation(path!!, entity.level().gameTime, selected)
        } else {
            search.rejectedRoutes.addAll(batch)
            DebugFlags.log(LogGroup.PATH, "[tactics] {} plan={} event=post_route_batch_rejected candidates={} attempts={} end={}", entity.uuid, task.plan, batch.size, search.pathAttempts, path?.endNode?.asBlockPos())
        }
    }

    private fun findInTurn(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot): Result {
        val level = entity.level() as ServerLevel
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return Result(Outcome.DEFERRED)
        val overwatch = task.job == TacticalJob.OVERWATCH && !task.localFireRecovery
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0 +
            if (task.job == TacticalJob.OVERWATCH) DefensiveOverwatch.RADIUS else 0.0
        val heldPosition = if (task.fireTransit == null) existingCoverPosition(entity, task, view, level, homeRadius) else null
        if (heldPosition != null) {
            DebugFlags.log(LogGroup.ORDER, "[tactics] {} plan={} event=position_kept position={} reason=usable_nearby_cover frontSlot={}", entity.uuid, task.plan, heldPosition, task.frontage?.point)
            return finish(task, heldPosition, allowDepthAdjustment = true, nearbyShelter = nearContact(task, heldPosition))
        }
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
        if (task.job !in TacticalJob.FIRE_SUPPORT) return null
        val member = view.members.firstOrNull { it.id == entity.uuid } ?: return null
        if (!TacticalEvidence.covers(member, task)) return null
        val point = member.position
        val atContact = nearContact(task, point)
        val post = task.localFirePost ?: task.staging ?: task.anchor
        val withinFront = task.frontage?.permitsFiringPost(point, covered = true, recovery = task.localFireRecovery) != false
        if (!atContact && !withinFront && !task.expandedFireSearch) return null
        if (!atContact && !TacticalFirePosts.withinPost(post, point, task.localFireRecovery)) return null
        if ((task.coverOrigin != null || task.expandedFireSearch) && FiringSpots.crowded(point, FiringSpots.nearbyWithBodies(level, entity, 64.0))) return null
        if (!TacticalRules.withinOrder(view, point, homeRadius) || GrenadeHazard.threatens(level, point)) return null
        if (!safeDistance(entity, view, point) || !TickBudget.hasRaycasts(level)) return null
        val aim = TacticalAim.resolve(entity, task) ?: return null
        val sheltered = DefensiveOverwatch.nearbyCover(point, aim.eye) { from, to -> Sightline.blocked(level, from, to, entity) }
        return point.takeIf { sheltered && firingReady(entity, task, point) }
    }

    private fun prepareSearch(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot, level: ServerLevel, homeRadius: Double, overwatch: Boolean): SearchContext {
        val taken = FiringSpots.nearbyWithBodies(level, entity, 64.0)
        val aim = TacticalAim.resolve(entity, task)
        val known = view.contacts.filter { view.now - it.seenAt <= 200 }.sortedByDescending { it.priority }
        val targetThreat = aim?.let { TacticalContact(it.target ?: java.util.UUID(0, 0), it.position, view.now) }
        val threats = (listOfNotNull(targetThreat) + known).distinctBy { it.id }.take(3)
        val hulls = Sightline.vehicleHulls(level, entity.boundingBox.inflate(64.0), entity, entity.target)
        val deployment = !task.expandedFireSearch && task.deployingFront(entity.position())
        val localAnchor = if (task.fireTransit != null) {
            task.fireTransit!!
        } else if (deployment) {
            task.frontage!!.leg(entity.position())
        } else {
            task.localFirePost ?: task.staging ?: if (task.job in TacticalRoutes.TRANSIT_JOBS) {
                TacticalRoutes.leg(entity.position(), task)
            } else {
                task.anchor
            }
        }
        val oldSearch = task.search
        val plan = entity.currentSquad()?.tactics?.plan
        val policy = plan?.behavior?.positions ?: DefaultTacticalPositions
        // Terrain and protection define a sentry post. Switching reported targets must not
        // restart its multi-tick height scan before it ever reaches the ladder.
        val searchAim = if (overwatch) null else aim
        if (oldSearch?.needsRestart(entity.position(), searchAim, deployment) == true) task.search = null
        if (overwatch && task.search == null) entity.navigation.stop()
        if (task.search == null) searchWorkObserver?.invoke(entity, true)
        val search = task.search ?: TacticalPositionSearch(
            entity.position(),
            (
                when {
                    task.fireTransit != null -> OFFSETS.map { localAnchor.add(it) }

                    overwatch -> DefensiveOverwatch.probes(task.anchor)

                    task.expandedFireSearch -> (TacticalFirePosts.probes(entity.position()) + TacticalFirePosts.probes(localAnchor, recovery = true)).distinct()

                    deployment -> (
                        OFFSETS.map { localAnchor.add(it) } +
                            if (entity.position().distanceTo(task.frontage!!.point) <= 48.0) task.frontage!!.probes(recovery = true) else emptyList()
                        ).distinct()

                    task.frontage != null -> task.frontage!!.probes(recovery = true)

                    task.job == TacticalJob.FLANK && localAnchor.subtract(task.anchor).horizontalDistance() < 1.0 ->
                        TacticalFrontPost(task.anchor, (task.focus ?: view.home ?: task.anchor).subtract(view.center).multiply(1.0, 0.0, 1.0).normalize()).probes(recovery = true)

                    task.job in TacticalJob.FIRING_POSITIONS -> listOf(entity.position()) + TacticalFirePosts.probes(localAnchor, task.localFireRecovery)

                    task.job == TacticalJob.FLANK -> OFFSETS.map { localAnchor.add(it) } +
                        TacticalRoutes.flankProbes(entity.position(), task, view, entity.npcClass)

                    else -> OFFSETS.map { localAnchor.add(it) }
                } + policy.additionalProbes(view, task, entity.position(), entity.npcClass) + (
                    task.coverOrigin?.takeUnless {
                        task.fireTransit != null || task.expandedFireSearch || (deployment && !TacticalContactApproach.searchesOriginShelter(view, entity.npcClass, it))
                    }?.let {
                        TacticalFirePosts.denseProbes(it, CombatPosition.LOCAL_MOVE_RADIUS.toInt())
                    } ?: emptyList()
                    )
                ).distinct(),
            searchAim,
            deployment,
        ).also { search ->
            task.search = search
            if (task.job == TacticalJob.FLANK && DebugFlags.on(LogGroup.ORDER)) {
                DebugFlags.log(
                    LogGroup.ORDER,
                    "[tactics] {} squad={} tick={} plan={} event=flank_search_context origin={} preferredLeg={} goal={} aim={} probes={} contacts={}",
                    entity.uuid, entity.currentSquad()?.id, level.gameTime, task.plan, entity.position(),
                    localAnchor, task.anchor, aim?.position, search.probes.size, known.map { it.id to it.position },
                )
            }
        }
        val covered = (plan?.behavior is CoordinatedAttackState && view.attackAssignment?.externalCover == true) || (
            plan != null && view.members.count { member ->
                plan.tasks[member.id]?.job in TacticalJob.FIRE_SUPPORT && TacticalEvidence.firesCover(member, plan.tasks[member.id])
            } >= TacticalFlanks.requiredCover(view)
            )
        return SearchContext(entity, task, view, level, homeRadius, overwatch, deployment, taken, threats, hulls, localAnchor, search, policy, covered, aim)
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
        val focus = context.focus
        val block = BlockPos.containing(raw)
        if (!level.chunkSource.hasChunk(block.x shr 4, block.z shr 4)) {
            search.reject("unloaded")
            return
        }
        val candidate = Terrain.feetAt(level, raw, if (context.overwatch) 9 else 12) {
            if (!level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(it))) {
                false
            } else if (context.overwatch && !protected(entity, task, it, context.hulls)) {
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
        val nearContact = nearContact(task, candidate)
        if (!search.visited.add(candidate)) return
        if (!TacticalFireAdvance.permits(task, search.origin, candidate)) {
            search.reject("no_transit_progress")
            return
        }
        if (!TacticalGoalProgress.permitsAdvance(task, search.origin, candidate, context.deployment)) {
            search.reject("no_goal_progress")
            return
        }
        if (!TacticalRules.withinOrder(context.view, candidate, context.homeRadius)) {
            search.reject("order")
            return
        }
        if (context.overwatch && !DefensiveOverwatch.within(task.anchor, candidate)) return
        if (task.job in TacticalJob.FIRING_POSITIONS && !context.overwatch &&
            !TacticalFirePosts.withinPost(context.localAnchor, candidate, task.localFireRecovery) && !nearContact
        ) {
            return
        }
        if (!context.policy.candidate(context.view, task, candidate, context.localAnchor)) return
        val front = task.frontage
        if (front != null && !task.expandedFireSearch) {
            val permitted = if (context.deployment) {
                front.acceptsDeployment(search.origin, candidate, task.localFireRecovery) || front.permits(candidate, recovery = true)
            } else {
                front.permits(candidate, recovery = true)
            }
            if (!permitted && !nearContact) {
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
        val exposure = context.threats.count {
            !Sightline.blockedBy(level, if (it.id == context.aim?.target) context.aim.eye else it.position.add(0.0, 1.5, 0.0), candidate.add(0.0, 0.9, 0.0), entity, context.hulls)
        }
        val mustShoot = requiresFiringLane(context, candidate)
        val aim = context.aim
        val firing = aim?.let {
            !Sightline.blockedBy(level, eye, it.eye, entity, context.hulls)
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
        val formationRejection = if (task.fireTransit == null) formationRejection(context, candidate, nearbyCover) else null
        if (formationRejection != null) {
            search.reject(formationRejection)
            return
        }
        search.scored[candidate] = score(context, candidate, exposure, firing, mustShoot, nearbyCover)
    }

    /** Owned lanes may give way to actual local shelter, never to an exposed backward post. */
    private fun formationRejection(context: SearchContext, candidate: Vec3, nearbyCover: Boolean): String? {
        val task = context.task
        val front = task.frontage ?: return null
        val nearContact = nearContact(task, candidate)
        val retreating = candidate.subtract(context.search.origin).dot(front.forward) < -TacticalFrontPost.DEPTH_TOLERANCE
        if (task.coverOrigin != null && retreating && !nearbyCover) return "uncovered_front_retreat"
        if (!task.expandedFireSearch && !front.permitsFiringPost(candidate, nearbyCover, task.localFireRecovery) &&
            !(nearContact && nearbyCover) && !context.deployment
        ) {
            return "open_depth_adjustment"
        }
        if (context.deployment && task.coverOrigin != null) {
            // Moving back is justified by a real nearby obstacle, never by the nominal line.
            val progressing = front.acceptsDeployment(context.search.origin, candidate, task.localFireRecovery)
            if ((!progressing || retreating) && !(nearbyCover && (nearContact || front.permits(candidate, recovery = true)))) {
                return "uncovered_deployment_retreat"
            }
        }
        if (nearContact && !nearbyCover && !task.expandedFireSearch &&
            !(if (context.deployment) front.acceptsDeployment(context.search.origin, candidate, task.localFireRecovery) else front.permits(candidate, recovery = true))
        ) {
            return "unprotected_contact_post"
        }
        return null
    }

    private fun score(context: SearchContext, candidate: Vec3, exposure: Int, firing: Boolean, mustShoot: Boolean, nearbyCover: Boolean): TacticalPositionScore {
        if (nearbyCover && nearContact(context.task, candidate)) {
            return TacticalPositionScore(false, exposure, candidate.distanceTo(context.search.origin))
        }
        if (context.deployment) {
            if (context.task.job == TacticalJob.ADVANCE) {
                // An explicit advance joins the moving group. Its transit endpoint is ranked
                // by route progress; the covering group owns selection of sheltered fire posts.
                return TacticalPositionScore.coveredTransitStep(context.search.origin, context.task.frontage!!.point, candidate)
            }
            return TacticalPositionScore(
                !nearbyCover,
                exposure,
                candidate.distanceTo(context.search.origin) * 0.1 + candidate.subtract(context.task.frontage!!.point).horizontalDistance(),
                TacticalPositionScore.Purpose.DEPLOYMENT,
            )
        }
        if (context.task.job == TacticalJob.FLANK && context.covered && !mustShoot) {
            return TacticalPositionScore.coveredTransitStep(context.entity.position(), context.task.anchor, candidate)
        }
        if (context.policy === CoordinatedAttackPositions && context.task.job in TacticalRoutes.TRANSIT_JOBS && !mustShoot) {
            // Lane deployment is a journey, not a local firing-post adjustment. Summing
            // distance from origin and to goal makes every point on the leg equally good
            // and repeatedly picks a tiny step. Live shelter/clearance checks ran above.
            return TacticalPositionScore.coveredTransitStep(context.search.origin, context.localAnchor, candidate)
        }
        var score = candidate.distanceTo(context.localAnchor) + candidate.distanceTo(context.entity.position())
        if (firing && mustShoot) score -= 8.0
        val front = context.task.frontage
        if (context.task.expandedFireSearch && front != null) {
            val side = Vec3(-front.forward.z, 0.0, front.forward.x)
            score += kotlin.math.abs(candidate.subtract(front.point).dot(side)) * 2.0
        }
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
            if (FiringPostArrival.reached(entity.position(), candidate) && safeDistance(entity, context.view, candidate)) {
                entity.navigation.stop()
                task.navigationPath = null
                FiringSpots.claim(entity.uuid, entity.position())
                tracePosition(context, entity.position(), search.scored[candidate])
                return finish(task, entity.position(), allowDepthAdjustment = canAdjustPostDepth(context, candidate), maneuverPost = task.job == TacticalJob.FLANK && mustShoot, nearbyShelter = isContactShelter(context, candidate))
            }
            if (search.routeValidation == null) {
                val batch = routeBatch(context, candidate, destinations)
                if (batch.isEmpty()) return Result(Outcome.DEFERRED)
                if (!TacticalBudget.path(entity)) {
                    search.pendingPath = batch
                    return Result(Outcome.DEFERRED)
                }
                val path = pathToCandidates(entity, batch)
                recordPath(entity, task, search, batch, path)
                candidate = search.routeValidation?.candidate ?: candidate
            }
            val validation = search.routeValidation
            val path = validation?.path
            if (reaches(path, candidate)) {
                val wasDeferred = validation!!.deferred
                val safety = checkRoute(context, validation)
                if (safety == TacticalRouteValidation.Outcome.DEFERRED) {
                    if (!wasDeferred) DebugFlags.log(LogGroup.PATH, "[tactics] {} plan={} event=post_route_deferred candidate={} node={}/{}", entity.uuid, task.plan, candidate, validation.nextNode, path!!.nodeCount)
                    return Result(Outcome.DEFERRED)
                }
                if (safety == TacticalRouteValidation.Outcome.SAFE && entity.navigation.moveTo(path, speed(task, entity.position()))) {
                    if (wasDeferred) DebugFlags.log(LogGroup.PATH, "[tactics] {} plan={} event=post_route_resumed candidate={} age={}", entity.uuid, task.plan, candidate, context.level.gameTime - validation.builtAt)
                    task.navigationPath = path
                    FiringSpots.claim(entity.uuid, candidate)
                    tracePosition(context, candidate, search.scored[candidate])
                    return finish(task, candidate, allowDepthAdjustment = canAdjustPostDepth(context, candidate), maneuverPost = task.job == TacticalJob.FLANK && mustShoot, nearbyShelter = isContactShelter(context, candidate))
                }
                search.reject(if (safety == TacticalRouteValidation.Outcome.SAFE) "navigation" else "unsafe_route")
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
            LogGroup.ORDER, "[tactics] {} squad={} tick={} plan={} event=position_selected job={} target={} position={} quality={} nearestEnemy={} rejected={} frontSlot={} deploying={} recovery={} from={} frontDisplacement={} shotRequired={} contactShelter={}",
            context.entity.uuid, context.entity.currentSquad()?.id, context.level.gameTime, context.task.plan, context.task.job, context.aim?.target, point, quality,
            TacticalClearance.threats(context.view).minOfOrNull { point.subtract(it).horizontalDistance() }, context.search.rejections,
            context.task.frontage?.point, context.deployment, context.task.localFireRecovery,
            context.search.origin, context.task.frontage?.let { point.subtract(context.search.origin).dot(it.forward) }, requiresFiringLane(context, point),
            isContactShelter(context, point),
        )
    }

    private fun requiresFiringLane(context: SearchContext, point: Vec3): Boolean =
        context.task.fireTransit == null &&
            (context.aim == null || WeaponReach.inRange(context.entity, point.add(0.0, context.entity.eyeHeight.toDouble(), 0.0), context.aim.eye)) &&
            (
                requiresFiringLane(context.task, context.deployment, point) ||
                    (context.task.job == TacticalJob.FLANK && context.localAnchor.subtract(context.task.anchor).horizontalDistance() < 1.0)
                )

    /** A transit leg may be blind; a post in the owned strip must already allow a shot. */
    internal fun requiresFiringLane(task: TacticalTask, deploying: Boolean, point: Vec3): Boolean =
        (task.job in SHOOTING_JOBS && task.job != TacticalJob.OVERWATCH && (!deploying || nearContact(task, point) || task.frontage?.permits(point, recovery = true) == true)) ||
            (task.job == TacticalJob.FLANK && point.distanceTo(task.anchor) <= 10.0)

    private fun canAdjustPostDepth(context: SearchContext, point: Vec3): Boolean =
        !context.deployment || (
            context.task.job in SHOOTING_JOBS && context.task.frontage?.permitsFiringPost(
                point,
                covered = context.search.scored[point]?.open == false,
                recovery = context.task.localFireRecovery,
            ) == true
            )

    private fun nearContact(task: TacticalTask, point: Vec3): Boolean = task.coverOrigin?.let { TacticalFirePosts.withinPost(it, point) } == true

    private fun isContactShelter(context: SearchContext, point: Vec3): Boolean = nearContact(context.task, point) && context.search.scored[point]?.open == false

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

    fun pathToCandidates(entity: NpcEntity, points: List<Vec3>): Path? =
        TacticalBudget.measure(entity) {
            val targets = points.mapTo(LinkedHashSet()) { VehicleAwareNavigation.walkingNode(it) }
            val navigation = entity.navigation
            if (navigation is VehicleAwareNavigation) navigation.createPositionPath(targets) else navigation.createPath(targets, 0)
        }

    private fun checkRoute(context: SearchContext, validation: TacticalRouteValidation): TacticalRouteValidation.Outcome {
        val path = validation.path
        val task = context.task
        val focus = context.focus
        if (!safePath(context.entity, context.view, path)) return TacticalRouteValidation.Outcome.UNSAFE
        for (index in path.nextNodeIndex until path.nodeCount) {
            val node = path.getNode(index)
            val foot = Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
            if (!TacticalRules.withinOrder(context.view, foot, context.homeRadius) || GrenadeHazard.threatens(context.level, foot) ||
                !context.policy.route(context.view, task, context.search.origin, foot)
            ) {
                return TacticalRouteValidation.Outcome.UNSAFE
            }
            if (task.opensLane && focus != null && !TacticalRoutes.safeLaneProbe(context.search.origin, foot, focus, task.probeAdvance)) return TacticalRouteValidation.Outcome.UNSAFE
        }
        return validation.resume { index ->
            if (index % 4 == 0 && task.job == TacticalJob.FLANK) {
                if (!TickBudget.hasRaycasts(context.level)) return@resume TacticalRouteValidation.Outcome.DEFERRED
                val node = path.getNode(index)
                val foot = Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
                if (hasUncoveredApproach(context, foot)) return@resume TacticalRouteValidation.Outcome.UNSAFE
            }
            TacticalRouteValidation.Outcome.SAFE
        }
    }

    fun safeDistance(entity: NpcEntity, view: TacticalSnapshot, point: Vec3): Boolean = TacticalClearance.permitsPosition(entity.npcClass, entity.position(), point, view)

    /** Repaths and already-running paths must respect newly reported or approaching enemies too. */
    fun safePath(entity: NpcEntity, view: TacticalSnapshot, path: Path): Boolean {
        if (!TacticalClearance.permitsPath(entity.npcClass, entity.position(), path, view)) return false
        val assignment = view.attackAssignment
        if (entity.currentSquad()?.tactics?.plan?.behavior !is CoordinatedAttackState || assignment == null || assignment.holding) return true
        return (path.nextNodeIndex until path.nodeCount).all {
            val node = path.getNode(it)
            assignment.corridor.permitsRoute(entity.position(), Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5))
        }
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
        return TacticalBudget.measure(entity) {
            if (navigation is VehicleAwareNavigation) {
                navigation.createPositionPath(VehicleAwareNavigation.walkingNode(point))
            } else {
                navigation.createPath(point.x, point.y, point.z, 0)
            }
        }
    }

    fun speed(task: TacticalTask, from: Vec3): Double =
        if (task.fireTransit != null || task.job in RUNNING_JOBS + setOf(TacticalJob.OVERWATCH, TacticalJob.SEARCH) || task.deployingFront(from) ||
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

    private fun protected(entity: NpcEntity, task: TacticalTask, point: Vec3, hulls: List<AABB>): Boolean =
        DefensiveOverwatch.protected(point, entity.eyeHeight.toDouble(), null) { from, to ->
            Sightline.blockedBy(entity.level() as ServerLevel, from, to, entity, hulls)
        } || (
            DefensiveOverwatch.protected(point, entity.eyeHeight.toDouble(), TacticalAim.resolve(entity, task)?.position) { from, to ->
                Sightline.blockedBy(entity.level() as ServerLevel, from, to, entity, hulls)
            } && friendlyLane(entity, point.add(0.0, entity.eyeHeight.toDouble(), 0.0), TacticalAim.resolve(entity, task)?.eye)
            )

    private fun finish(task: TacticalTask, point: Vec3, allowDepthAdjustment: Boolean = false, maneuverPost: Boolean = false, nearbyShelter: Boolean = false): Result {
        if (nearbyShelter && task.frontage?.contains(point) == false) {
            // Reuse local firing-post recovery to own this validated shelter, rather than
            // clearing it as a deployment leg on arrival and sending the actor back to the line.
            task.localFirePost = point
            task.expandedFireSearch = true
        } else if (allowDepthAdjustment && !task.expandedFireSearch && task.frontage?.contains(point) == false) {
            task.localFirePost = task.frontage!!.point
        }
        task.search = null
        return Result(Outcome.FOUND, point, maneuverPost = maneuverPost)
    }
    private fun safeBody(entity: NpcEntity, point: Vec3, body: AABB, hulls: List<AABB>, taken: List<Vec3>): Boolean =
        entity.level().getFluidState(BlockPos.containing(point)).isEmpty && entity.level().noCollision(entity, body) &&
            hulls.none { it.intersects(body) } && !GrenadeHazard.threatens(entity.level() as ServerLevel, point) && !FiringSpots.crowded(point, taken)
    private fun friendlyLane(entity: NpcEntity, eye: Vec3, targetEye: Vec3?): Boolean = targetEye == null || FriendlyFireGuard.assess(entity, targetEye, entity.spread, targetEye, 0.0, eye).lineClear

    /** Physical post suitability only; personal visibility must still authorize aimed fire at the weapon. */
    fun firingReady(entity: NpcEntity, task: TacticalTask, point: Vec3): Boolean {
        val aim = TacticalAim.resolve(entity, task) ?: return true
        val eye = point.add(0.0, entity.eyeHeight.toDouble(), 0.0)
        if (!WeaponReach.inRange(entity, eye, aim.eye)) return true
        val level = entity.level() as ServerLevel
        val hulls = Sightline.vehicleHulls(level, AABB(eye, aim.eye).inflate(2.0), entity, entity.target)
        return !Sightline.blockedBy(level, eye, aim.eye, entity, hulls, entity.spread) &&
            friendlyLane(entity, eye, aim.eye)
    }
    private const val MAX_PROBES_PER_TICK = 24
    private const val DENSE_CANDIDATE_BATCH = 16
    private val SHOOTING_JOBS = TacticalJob.FIRING_POSITIONS
    private val RUNNING_JOBS = TacticalJob.RUNNING
    private val OFFSETS = listOf(
        Vec3.ZERO, Vec3(0.8, 0.0, 0.0), Vec3(-0.8, 0.0, 0.0), Vec3(0.0, 0.0, 0.8), Vec3(0.0, 0.0, -0.8),
        Vec3(4.0, 0.0, 0.0), Vec3(-4.0, 0.0, 0.0), Vec3(0.0, 0.0, 4.0), Vec3(0.0, 0.0, -4.0),
        Vec3(4.0, 0.0, 4.0), Vec3(-4.0, 0.0, 4.0), Vec3(4.0, 0.0, -4.0), Vec3(-4.0, 0.0, -4.0),
    )
}
