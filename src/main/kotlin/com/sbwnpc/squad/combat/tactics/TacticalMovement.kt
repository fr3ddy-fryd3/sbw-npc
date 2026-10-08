package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import net.minecraft.server.level.ServerLevel

/** Executes owned movement tasks through the existing navigator, cover and medical priorities. */
object TacticalMovement {
    /** Core behavior calls this even while the individual mover is suspended by another behavior. */
    fun observe(entity: NpcEntity) {
        val squad = entity.currentSquad() ?: return
        val plan = squad.tactics.plan ?: return
        if (plan.stamp != squad.orderStamp) return
        val task = plan.tasks[entity.uuid] ?: return
        task.observeBlocker(entity.uuid, entity.level().gameTime, blocker(entity), squad.tactics.events)
    }

    private fun blocker(entity: NpcEntity): TacticalMovementBlocker? =
        when {
            entity.vehicle != null -> TacticalMovementBlocker.VEHICLE
            entity.busyWithRole() -> TacticalMovementBlocker.ROLE_TASK
            entity.resupplying -> TacticalMovementBlocker.SUPPLY
            entity.diggedIn -> TacticalMovementBlocker.DUG_IN
            entity.evadingGrenade() -> TacticalMovementBlocker.GRENADE
            entity.movementLockedByCover() -> TacticalMovementBlocker.COVER
            entity.combatLockedByMedic() -> TacticalMovementBlocker.MEDIC
            entity.retreatPoint() != null -> TacticalMovementBlocker.RETREAT
            else -> null
        }

    /** True means tactical movement owns the feet this tick; cover/medical/vehicles outrank it. */
    fun move(entity: NpcEntity): Boolean {
        observe(entity)
        if (blocker(entity) != null) return false
        val task = SquadTactics.task(entity) ?: return false
        // Finish the physical jump before ordinary post validation or phase changes own the feet.
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return true
        val squad = entity.currentSquad() ?: return false
        val state = squad.tactics
        val view = state.snapshot ?: return false
        if (state.plan?.phase == TacticalPhase.PAUSED && task.job in RUNNING_JOBS) {
            if (task.suspend(entity.level().gameTime, "cover_lost")) {
                state.events.emit(TacticalEvent.TaskPaused(task.plan, entity.level().gameTime, entity.uuid, true, "cover_lost"))
            }
            entity.navigation.stop()
            return true
        }
        if (task.resume(entity.level().gameTime)) {
            state.events.emit(TacticalEvent.TaskPaused(task.plan, entity.level().gameTime, entity.uuid, false, "cover_restored"))
        }
        if (task.job == TacticalJob.WAIT && task.staging == null) {
            entity.navigation.stop()
            return true
        }
        val activePath = entity.navigation.path
        val destination = task.position
        val unsafeDestination = destination != null && !TacticalPositions.safeDistance(entity, view, destination)
        val unsafeRoute = activePath != null && !activePath.isDone && !TacticalPositions.safePath(entity, view, activePath)
        if (unsafeDestination || unsafeRoute) {
            entity.navigation.stop()
            task.navigationPath = null
            task.position = null
            task.search = null
            task.nextSearch = entity.level().gameTime + 10
            FiringSpots.release(entity.uuid)
            DebugFlags.log(
                LogGroup.ORDER,
                "[tactics] {} squad={} tick={} plan={} event=position_invalidated job={} reason=enemy_clearance",
                entity.uuid,
                squad.id,
                entity.level().gameTime,
                task.plan,
                task.job,
            )
            return true
        }
        val previousTarget = task.firingTarget
        val postReview = TacticalPostReview.review(task, entity.target?.uuid, entity.level().gameTime) {
            val level = entity.level() as ServerLevel
            if (!TickBudget.hasRaycasts(level)) {
                null
            } else {
                val point = task.position!!
                val requiresCover = task.job == TacticalJob.OVERWATCH && !task.localFireRecovery
                !GrenadeHazard.threatens(level, point) &&
                    (!requiresCover || TacticalPositions.protected(entity, task, point)) &&
                    TacticalPositions.firingReady(entity, task, point)
            }
        }
        if (postReview == TacticalPostReview.Result.INVALIDATED) {
            task.releaseNavigation(entity.navigation.path) { entity.navigation.stop() }
            FiringSpots.release(entity.uuid)
            task.position = null
            task.search = null
            task.nextSearch = entity.level().gameTime
            DebugFlags.log(
                LogGroup.ORDER, "[tactics] {} squad={} tick={} plan={} event=position_invalidated job={} reason=target_changed previous={} target={}",
                entity.uuid, squad.id, entity.level().gameTime, task.plan, task.job, previousTarget, entity.target?.uuid,
            )
        } else if (postReview == TacticalPostReview.Result.RETAINED) {
            DebugFlags.log(
                LogGroup.ORDER, "[tactics] {} squad={} tick={} plan={} event=position_retained job={} reason=target_changed previous={} target={} position={}",
                entity.uuid, squad.id, entity.level().gameTime, task.plan, task.job, previousTarget, entity.target?.uuid, task.position,
            )
        }
        val reviewDue = task.job in TacticalJob.FIRE_SUPPORT && !task.deployingFront(entity.position()) && entity.level().gameTime >= task.nextValidation &&
            task.position?.let { FiringPostArrival.reached(entity.position(), it) } == true
        if (postReview != TacticalPostReview.Result.DEFERRED &&
            (task.position == null || task.search != null || reviewDue) && entity.level().gameTime >= task.nextSearch
        ) {
            val result = TacticalPositions.find(entity, task, view)
            when (result.outcome) {
                TacticalPositions.Outcome.DEFERRED -> {
                    task.nextSearch = entity.level().gameTime + 2
                    return true
                }

                TacticalPositions.Outcome.UNREACHABLE -> {
                    task.nextSearch = entity.level().gameTime + 30
                    DebugFlags.log(
                        LogGroup.ORDER, "[tactics] {} squad={} tick={} plan={} event=position_search_failed job={} unreachable={} from={} staging={} opensLane={} rejected={} attempt={} frontSlot={} recovery={}",
                        entity.uuid, squad.id, entity.level().gameTime, task.plan, task.job, task.anchor, entity.position(), task.staging, task.opensLane, result.rejections, task.failures + 1, task.frontage?.point, task.localFireRecovery,
                    )
                    return !failedTask(entity, task, "no reachable position", completedSearch = true)
                }

                TacticalPositions.Outcome.FOUND -> {
                    task.position = result.position
                    task.maneuverPost = result.maneuverPost
                    task.firingTarget = entity.target?.uuid
                    task.failures = 0
                    task.closest = Double.MAX_VALUE
                    task.lastProgress = entity.level().gameTime
                    task.nextSearch = entity.level().gameTime + 20
                    task.nextValidation = entity.level().gameTime + if (task.job in TacticalJob.FIRE_SUPPORT) 100 else 40
                    if (task.job == TacticalJob.OVERWATCH) {
                        DebugFlags.log(
                            LogGroup.ORDER,
                            "[tactics] {} overwatch={} anchor={} height={}",
                            entity.uuid,
                            task.position,
                            task.anchor,
                            task.position!!.y - task.anchor.y,
                        )
                    }
                }
            }
        }
        val position = task.position ?: return true
        FiringSpots.claim(entity.uuid, position)
        val distance = entity.position().distanceTo(position)
        if (distance < task.closest - 0.5) {
            task.closest = distance
            task.lastProgress = entity.level().gameTime
        }
        val firingPost = requiresPreciseArrival(task)
        val arrived = reachedTaskPosition(task, entity.position())
        if (arrived) {
            if (task.deployingFront(position)) {
                task.position = null
                task.search = null
                task.nextSearch = entity.level().gameTime + 2
                FiringSpots.release(entity.uuid)
                return true
            }
            if (task.job in TacticalJob.FIRING_POSITIONS && entity.level().gameTime >= task.nextValidation &&
                TickBudget.hasRaycasts(entity.level() as ServerLevel)
            ) {
                task.nextValidation = entity.level().gameTime + 40
                val invalidCover = task.job == TacticalJob.OVERWATCH && !task.localFireRecovery && !TacticalPositions.protected(entity, task, entity.position())
                if (GrenadeHazard.threatens(entity.level() as ServerLevel, position) || invalidCover || !TacticalPositions.firingReady(entity, task, entity.position())) {
                    DebugFlags.log(
                        LogGroup.ORDER,
                        "[tactics] {} squad={} tick={} plan={} event=position_invalidated job={} reason=firing_post_changed target={}",
                        entity.uuid,
                        squad.id,
                        entity.level().gameTime,
                        task.plan,
                        task.job,
                        entity.target?.uuid,
                    )
                    task.position = null
                    task.search = null
                    task.nextSearch = entity.level().gameTime + 10
                    FiringSpots.release(entity.uuid)
                    return true
                }
            }
            if (task.job in RUNNING_JOBS + TacticalJob.SEARCH && !task.maneuverPost && !TacticalGoalProgress.reached(task, entity.position())) {
                task.position = null
                task.search = null
                task.nextSearch = entity.level().gameTime + 5
            } else {
                entity.navigation.stop()
            }
            if (task.job in setOf(TacticalJob.OBSERVE, TacticalJob.SEARCH, TacticalJob.RESERVE)) {
                val aim = task.focus ?: position.add(entity.lookAngle.scale(8.0))
                val sweep = kotlin.math.sin(entity.tickCount / 30.0 + entity.uuid.leastSignificantBits % 7) * 4.0
                entity.lookControl.setLookAt(aim.x + sweep, aim.y + 1.5, aim.z - sweep)
            }
            return true
        }
        if (entity.level().gameTime - task.lastProgress >= 100) {
            if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return true
            task.position = null
            task.search = null
            task.nextSearch = entity.level().gameTime + 20
            return !failedTask(entity, task, "no movement progress for 100 ticks")
        }
        if (firingPost && FiringPostArrival.steer(entity, position, TacticalPositions.speed(task))) return true
        if (entity.navigation.isDone && entity.level().gameTime >= task.nextSearch) {
            task.nextSearch = entity.level().gameTime + 20
            if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) {
                task.nextSearch = entity.level().gameTime + 2
                return true
            }
            if (!TacticalBudget.path(entity.level().gameTime)) {
                task.nextSearch = entity.level().gameTime + 2
                return true
            }
            val path = TacticalPositions.pathToPosition(entity, position)
            if (TacticalPositions.reaches(path, position) && TacticalPositions.safePath(entity, view, path!!)) {
                if (entity.navigation.moveTo(path, TacticalPositions.speed(task))) task.navigationPath = path
            } else {
                task.position = null
                task.search = null
                return !failedTask(entity, task, "position path no longer reaches destination")
            }
        }
        return true
    }

    private fun failedTask(entity: NpcEntity, task: TacticalTask, reason: String, completedSearch: Boolean = false): Boolean {
        if (task.localFireRecovery) {
            if (TacticalFirePosts.expand(task, entity.position())) {
                task.nextSearch = entity.level().gameTime + 2
                DebugFlags.log(
                    LogGroup.ORDER,
                    "[tactics] {} squad={} tick={} plan={} event=fire_post_expanded job={} origin={} radius={} reason={}",
                    entity.uuid, entity.currentSquad()?.id, entity.level().gameTime, task.plan, task.job,
                    task.localFirePost, TacticalFirePosts.RECOVERY_RADIUS, reason,
                )
                return false
            }
            task.nextSearch = entity.level().gameTime + 100
            DebugFlags.log(LogGroup.ORDER, "[tactics] {} plan={} event=fire_post_retry job={} reason={}", entity.uuid, task.plan, task.job, reason)
            return false
        }
        val recoverNow = completedSearch && TacticalFirePosts.recoverAfterSearch(task, entity.position())
        if (++task.failures < if (task.job == TacticalJob.OVERWATCH || recoverNow) 1 else 3) return false
        val plan = entity.currentSquad()?.tactics?.plan ?: return true
        if (TacticalFirePosts.recover(task, entity.position())) {
            task.releaseNavigation(entity.navigation.path) { entity.navigation.stop() }
            FiringSpots.release(entity.uuid)
            DebugFlags.log(
                LogGroup.ORDER,
                "[tactics] {} plan={} event=fire_post_recovery job={} reason={} failures={}",
                entity.uuid,
                task.plan,
                task.job,
                reason,
                task.failures,
            )
            return false
        }
        if (plan.behavior.holdsFailedMembers) {
            entity.currentSquad()?.tactics?.holdAfterFailure?.set(entity.uuid, entity.level().gameTime + 120)
        }
        TacticalCoordinator.abandon(plan, entity.uuid, entity.level().gameTime, reason)
        if (DebugFlags.on(LogGroup.ORDER)) {
            DebugFlags.log(
                LogGroup.ORDER,
                "[tactics] {} plan={} event=task_failed pattern={} job={} reason={} failures={} fallback={} remaining={}",
                entity.uuid, plan.id, plan.pattern, task.job, reason, task.failures,
                if (plan.behavior.holdsFailedMembers) "hold_position" else "individual", jobs(plan),
            )
        }
        return true
    }

    private fun jobs(plan: TacticalPlan) = plan.tasks.values.groupingBy { it.job }.eachCount()

    private fun requiresPreciseArrival(task: TacticalTask): Boolean = task.frontage != null || task.job in TacticalJob.FIRING_POSITIONS || task.job == TacticalJob.FLANK

    internal fun reachedTaskPosition(task: TacticalTask, actual: net.minecraft.world.phys.Vec3): Boolean {
        val position = task.position ?: return false
        return if (requiresPreciseArrival(task)) FiringPostArrival.reached(actual, position) else actual.distanceTo(position) <= 2.0
    }

    /** Moving groups keep their path; stationary shooters request a new coordinated position. */
    fun repositionForFire(entity: NpcEntity) {
        val task = SquadTactics.task(entity) ?: return
        if (!invalidateReachedFirePost(task, entity.position(), entity.onGround(), entity.level().gameTime)) return
        FiringSpots.release(entity.uuid)
    }

    internal fun invalidateReachedFirePost(task: TacticalTask, actual: net.minecraft.world.phys.Vec3, grounded: Boolean, now: Long): Boolean {
        val position = task.position ?: return false
        if (task.deployingFront(position)) return false
        if (task.job !in TacticalJob.FIRING_POSITIONS || !grounded || !FiringPostArrival.reached(actual, position)) return false
        task.position = null
        task.search = null
        task.nextSearch = now + 10
        return true
    }

    private val RUNNING_JOBS = TacticalJob.RUNNING
}
