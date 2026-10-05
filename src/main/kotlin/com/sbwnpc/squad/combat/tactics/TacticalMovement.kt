package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.entity.NpcEntity
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

    private fun blocker(entity: NpcEntity): TacticalMovementBlocker? = when {
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
        val squad = entity.currentSquad() ?: return false
        val state = squad.tactics
        val view = state.snapshot ?: return false
        if (state.plan?.phase==TacticalPhase.PAUSED && task.job in RUNNING_JOBS) {
            if (task.suspend(entity.level().gameTime,"cover_lost")) state.events.emit(
                TacticalEvent.TaskPaused(task.plan,entity.level().gameTime,entity.uuid,true,"cover_lost"))
            entity.navigation.stop()
            return true
        }
        if (task.resume(entity.level().gameTime)) state.events.emit(
            TacticalEvent.TaskPaused(task.plan,entity.level().gameTime,entity.uuid,false,"cover_restored"))
        if (task.job == TacticalJob.WAIT && task.staging==null) { entity.navigation.stop(); return true }
        if (task.position == null && entity.level().gameTime >= task.nextSearch) {
            val result = TacticalPositions.find(entity, task, view)
            when (result.outcome) {
                TacticalPositions.Outcome.DEFERRED -> { task.nextSearch = entity.level().gameTime + 2; return true }
                TacticalPositions.Outcome.UNREACHABLE -> {
                    task.nextSearch = entity.level().gameTime + 30
                    DebugFlags.log(LogGroup.ORDER,"[tactics] {} plan={} job={} unreachable={} from={} staging={} opensLane={} rejected={} attempt={}",
                        entity.uuid,task.plan,task.job,task.anchor,entity.position(),task.staging,task.opensLane,result.rejections,task.failures+1)
                    return !failedTask(entity,task,"no reachable position")
                }
                TacticalPositions.Outcome.FOUND -> {
                    task.position = result.position
                    task.closest = Double.MAX_VALUE
                    task.lastProgress = entity.level().gameTime
                    task.nextSearch = entity.level().gameTime + 20
                    task.nextValidation=entity.level().gameTime+40
                    if (task.job==TacticalJob.OVERWATCH) DebugFlags.log(LogGroup.ORDER,
                        "[tactics] {} overwatch={} anchor={} height={}",entity.uuid,task.position,task.anchor,task.position!!.y-task.anchor.y)
                }
            }
        }
        val position = task.position ?: return true
        FiringSpots.claim(entity.uuid,position)
        val distance = entity.position().distanceTo(position)
        if (distance < task.closest - 0.5) { task.closest = distance; task.lastProgress = entity.level().gameTime }
        if (distance <= if (task.job==TacticalJob.OVERWATCH) 0.9 else 2.0) {
            if (task.job==TacticalJob.OVERWATCH && entity.level().gameTime>=task.nextValidation &&
                TickBudget.hasRaycasts(entity.level() as ServerLevel)) {
                task.nextValidation=entity.level().gameTime+40
                if (GrenadeHazard.threatens(entity.level() as ServerLevel,position) || !TacticalPositions.protected(entity,task,entity.position())) {
                    task.position=null
                    task.search=null
                    task.nextSearch=entity.level().gameTime+10
                    FiringSpots.release(entity.uuid)
                    return true
                }
            }
            if (task.job in RUNNING_JOBS + TacticalJob.SEARCH && entity.position().distanceTo(task.anchor) > 10.0) {
                task.position = null
                task.search = null
                task.nextSearch = entity.level().gameTime + 5
            } else entity.navigation.stop()
            if (task.job in setOf(TacticalJob.OBSERVE,TacticalJob.SEARCH,TacticalJob.RESERVE)) {
                val aim = task.focus ?: position.add(entity.lookAngle.scale(8.0))
                val sweep = kotlin.math.sin(entity.tickCount/30.0+entity.uuid.leastSignificantBits%7)*4.0
                entity.lookControl.setLookAt(aim.x+sweep,aim.y+1.5,aim.z-sweep)
            }
            return true
        }
        if (entity.level().gameTime - task.lastProgress >= 100) {
            if ((entity.navigation as? com.sbwnpc.squad.entity.ai.VehicleAwareNavigation)?.canPlan == false) return true
            task.position = null
            task.search = null
            task.nextSearch = entity.level().gameTime + 20
            return !failedTask(entity,task,"no movement progress for 100 ticks")
        }
        if (entity.navigation.isDone && entity.level().gameTime >= task.nextSearch) {
            task.nextSearch = entity.level().gameTime + 20
            if ((entity.navigation as? com.sbwnpc.squad.entity.ai.VehicleAwareNavigation)?.canPlan == false) {
                task.nextSearch = entity.level().gameTime + 2
                return true
            }
            if (!TacticalBudget.path(entity.level().gameTime)) { task.nextSearch = entity.level().gameTime + 2; return true }
            val path = TacticalPositions.pathToPosition(entity,position)
            if (TacticalPositions.reaches(path,position)) {
                if (entity.navigation.moveTo(path,TacticalPositions.speed(task))) task.navigationPath=path
            }
            else { task.position = null; task.search = null; return !failedTask(entity,task,"position path no longer reaches destination") }
        }
        return true
    }

    private fun failedTask(entity: NpcEntity,task: TacticalTask,reason: String): Boolean {
        if (++task.failures < if (task.job==TacticalJob.OVERWATCH) 1 else 3) return false
        val plan = entity.currentSquad()?.tactics?.plan ?: return true
        if (plan.behavior.holdsFailedMembers)
            entity.currentSquad()?.tactics?.holdAfterFailure?.set(entity.uuid,entity.level().gameTime+120)
        TacticalCoordinator.abandon(plan,entity.uuid,entity.level().gameTime,reason)
        if (DebugFlags.on(LogGroup.ORDER)) DebugFlags.log(LogGroup.ORDER,
            "[tactics] {} plan={} pattern={} job={} reason={} failures={} individual fallback, remaining={}",
            entity.uuid,plan.id,plan.pattern,task.job,reason,task.failures,jobs(plan))
        return true
    }

    private fun jobs(plan: TacticalPlan) = plan.tasks.values.groupingBy { it.job }.eachCount()

    /** Moving groups keep their path; stationary shooters request a new coordinated position. */
    fun repositionForFire(entity: NpcEntity) {
        val task=SquadTactics.task(entity) ?: return
        val position=task.position ?: return
        if (task.job !in setOf(TacticalJob.COVER,TacticalJob.ANTI_ARMOUR,TacticalJob.OVERWATCH) || entity.position().distanceTo(position)>2.0) return
        task.position=null
        task.search=null
        task.nextSearch=entity.level().gameTime+10
        FiringSpots.release(entity.uuid)
    }

    private val RUNNING_JOBS = TacticalJob.RUNNING
}
