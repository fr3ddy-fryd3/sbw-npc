package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One assessment per squad; existing individual behaviours retain weapons and emergency actions. */
object SquadTactics {
    /** Called only after the existing sensor or gun behaviour has actually seen this enemy. */
    fun observe(npc: NpcEntity, target: LivingEntity) {
        if (npc.vehicle != null || npc.npcClass !in GROUND_ROLES || !target.isAlive || !npc.isEnemy(target)) return
        val squad = npc.currentSquad() ?: return
        val state = squad.tactics
        val now = npc.level().gameTime
        val old = state.contacts[target.uuid]
        val velocity = if (old != null && now > old.seenAt) target.position().subtract(old.position).scale(1.0 / (now - old.seenAt)) else target.deltaMovement
        val dangerous = target is NpcEntity && target.npcClass in SUPPORT_ROLES
        state.contacts[target.uuid] = TacticalContact(target.uuid, target.position(), now, velocity,
            target.vehicle?.let { Ports.vehicles.isOperational(it) &&
                (Ports.vehicles.modelOf(it) is com.sbwnpc.squad.npc.TankModel ||
                 Ports.vehicles.modelOf(it) is com.sbwnpc.squad.npc.TransportVehicle) } == true,
            1.0 + (if (dangerous) 1.0 else 0.0) + (if (target.uuid == squad.focusEntity) 5.0 else 0.0),
            target.vehicle?.let { Ports.vehicles.mobility(it) == com.sbwnpc.squad.domain.port.Mobility.AIR } == true)
    }

    fun refresh(entity: NpcEntity) {
        val squad = entity.currentSquad() ?: return
        val level = entity.level() as? ServerLevel ?: return
        val state = squad.tactics
        val now = level.gameTime
        if (now < state.nextAssessment && state.plan?.stamp == squad.orderStamp) return
        state.nextAssessment = now + 10
        val npcs = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }
            .filter { it.isAlive && it.vehicle == null && it.npcClass in GROUND_ROLES }
        if (npcs.isEmpty()) return
        if (state.flankSide == 0.0) state.flankSide = if (squad.id.leastSignificantBits and 1L == 0L) 1.0 else -1.0
        val center = average(npcs.map { it.position() })
        val seen = HashSet<UUID>()
        for (npc in npcs) {
            val target = npc.target ?: continue
            if (!target.isAlive || !npc.isEnemy(target) || npc.distanceToSqr(target) > NpcEntity.DETECTION_RANGE * NpcEntity.DETECTION_RANGE) continue
            if (target.uuid in seen || !DetectionSightline.canSee(npc, target)) continue
            seen += target.uuid
            observe(npc,target)
        }
        state.contacts.entries.removeIf { now - it.value.seenAt > 200 }
        val members = npcs.map { npc ->
            val target = npc.target
            val gun = Ports.guns.inHand(npc)
            val ready = (gun?.hasAmmo() == true || AntiArmourKit.loaded(npc)) && !npc.busyWithRole() && !npc.resupplying && !npc.combatLockedByMedic() && !npc.evadingGrenade()
            TacticalMember(npc.uuid, npc.position(), npc.npcClass, (npc.health / npc.maxHealth).toDouble(), ready,
                ready && !npc.combatLockedByCover() && (npc.readyToCover || npc.firedRecently(40)) && target != null &&
                    target.uuid in seen && DetectionSightline.canSee(npc, target) && npc.sensing.hasLineOfSight(target),
                npc.isSuppressed(), AntiArmourKit.loaded(npc),
                if (npc.readyToCover && target?.uuid in seen) target?.position() else npc.lastFireAt,
                recentFire=npc.firedRecently(40),recentFireAt=npc.lastFireAt)
        }
        state.peakStrength = maxOf(state.peakStrength, members.size)
        val knownFocus = state.contacts.values.filter { now - it.seenAt < 40 }.maxByOrNull { it.priority }?.position
        val terrain = TacticalTerrain.assess(level, npcs, knownFocus ?: entity.homeCenter(), knownFocus)
        val engaged=npcs.filter { it.target!=null && it.npcClass!=NpcClass.MEDIC && !it.busyWithRole() && !it.resupplying }
        val view = TacticalSnapshot(now, squad.order, squad.orderStamp, center, entity.homeCenter(), members,
            state.contacts.values.filter { it.position.distanceTo(center) <= 96.0 }, npcs.mapNotNull { it.incomingFire.recentPoint(now) },
            npcs.any { it.evadingGrenade() || GrenadeHazard.threatens(level, it.position()) },
            narrow = terrain.narrow, open = terrain.open,
            stalled = TacticalRules.stalled(engaged.count { !it.firedRecently(40) && it.blockedSightSince?.let { since -> it.tickCount-since>=80 }==true },engaged.size),
            peakStrength = state.peakStrength)
        val defensiveHomeMoved=squad.order == SquadOrder.DEFEND && state.snapshot?.home != null && view.home != null &&
            state.snapshot!!.home!!.distanceTo(view.home)>8.0
        if (defensiveHomeMoved) state.plan?.status=TacticalStatus.COMPLETED
        state.snapshot = view
        val proposed = TacticalRules.choose(view)
        var choice = proposed
        if (choice.pattern != TacticalPattern.EVADE && now < (state.blocked[choice.pattern] ?: Long.MIN_VALUE))
            choice = TacticalChoice(if (view.visible.isNotEmpty()) TacticalPattern.REORIENT else TacticalPattern.FOLLOW_ORDER,
                choice.focus,reason=TacticalReason.PATTERN_COOLDOWN)
        val previousTasks = state.plan?.tasks?.toMap().orEmpty()
        val sameDefensiveOrder=!defensiveHomeMoved && state.plan?.stamp==squad.orderStamp && state.plan?.pattern in DefensiveOverwatch.PATTERNS
        val previousStatus = state.plan?.status
        val previousPlan = state.plan
        val previousBounds = previousPlan?.bounds
        val selected = state.select(choice, squad.orderStamp, now)
        if (selected) {
            state.plan?.let { plan ->
                TacticalManeuvers.assign(squad.id, plan, view)
                if (sameDefensiveOrder) DefensiveOverwatch.preservePosts(plan,previousTasks,view)
                if (DebugFlags.on(LogGroup.ORDER)) DebugFlags.log(LogGroup.ORDER,
                    "[tactics] {} plan={} pattern={} reason={} previousPlan={} previousPattern={} previousPhase={} orderChanged={} members={} contacts={} center={} focus={} jobs={}",
                    squad.name,plan.id,plan.pattern,plan.reason,previousPlan?.id,previousPlan?.pattern,previousStatus,
                    previousPlan!=null && previousPlan.stamp!=squad.orderStamp,members.size,view.visible.size,view.center,plan.focus,jobs(plan))
            }
        }
        state.plan?.let { plan ->
            TacticalManeuvers.refreshSectors(plan,view)
            TacticalManeuvers.advance(squad.id,state,plan,view)
            val phaseChanged = plan.status != previousStatus || plan.bounds != previousBounds
            if (phaseChanged && DebugFlags.on(LogGroup.ORDER)) DebugFlags.log(LogGroup.ORDER,
                "[tactics] {} plan={} phase={} bounds={} failure={} jobs={}",
                squad.name,plan.id,plan.status,plan.bounds,plan.failure,jobs(plan))
            trace(squad,view,proposed,choice,selected || phaseChanged)
        }
        for ((id,old) in previousTasks) if (state.plan?.tasks?.get(id) !== old) FiringSpots.release(id)
    }

    /** One heartbeat per five seconds, plus actual plan/phase changes; no per-tick log scans. */
    private fun trace(squad: Squad,view: TacticalSnapshot,proposed: TacticalChoice,effective: TacticalChoice,changed: Boolean) {
        if (!DebugFlags.on(LogGroup.ORDER)) return
        val state=squad.tactics
        if (!changed && view.now<state.nextTrace) return
        val plan=state.plan ?: return
        state.nextTrace=view.now+100
        DebugFlags.log(LogGroup.ORDER,
            "[tactics] {} snapshot squad={} tick={} plan={} pattern={} phase={} reason={} proposed={} proposedReason={} effective={} cooldown={} order={} stamp={} members={} peak={} fighting={} visible={} remembered={} incoming={} suppressed={} narrow={} open={} stalled={} heightDelta={} cover={} shotsLast40Ticks={} coverAge={} phaseAge={} bounds={} openingLane={} laneAttempts={} positioned={} failed={} jobs={}",
            squad.name,squad.id,view.now,plan.id,plan.pattern,plan.status,plan.reason,proposed.pattern,proposed.reason,
            effective.pattern,effective.reason==TacticalReason.PATTERN_COOLDOWN,view.order,view.stamp,view.members.size,
            view.peakStrength,view.fighting.size,view.visible.size,view.contacts.size,view.incoming.size,
            view.members.count { it.suppressed },view.narrow,view.open,view.stalled,plan.focus?.y?.minus(view.center.y),
            TacticalTelemetry.cover(plan,view),view.members.count { it.recentFire },view.now-plan.lastCover,
            view.now-plan.phaseSince,plan.bounds,plan.openingLane,plan.laneAttempts,
            plan.tasks.values.count { it.position!=null },plan.failedMembers.size,jobs(plan))
    }

    fun task(entity: NpcEntity): TacticalTask? {
        refresh(entity)
        val squad = entity.currentSquad() ?: return null
        val plan = squad.tactics.plan ?: return null
        if (plan.stamp != squad.orderStamp || plan.status == TacticalStatus.FAILED) return null
        return plan.tasks[entity.uuid]
    }

    fun hasTask(entity: NpcEntity): Boolean = task(entity) != null

    /** A failed combat route yields local firing-position work, rather than a frontal assault. */
    fun holdsAfterFailure(entity: NpcEntity): Boolean {
        val squad=entity.currentSquad() ?: return false
        return squad.tactics.holdsAfterFailure(entity.uuid,squad.orderStamp,entity.level().gameTime)
    }

    fun equip(entity: NpcEntity) {
        if (entity.vehicle != null || entity.busyWithRole() || entity.combatLockedByMedic() || entity.resupplying) return
        val task = task(entity) ?: return
        val target = entity.target ?: return
        if (task.job == TacticalJob.ANTI_ARMOUR && AntiArmourKit.loaded(entity) &&
            AntiArmourKit.worthARocket(entity,target) && DetectionSightline.canSee(entity,target)) AntiArmourKit.wield(entity,true)
    }

    /** True means tactical movement owns the feet this tick; cover/medical/vehicles outrank it. */
    fun move(entity: NpcEntity): Boolean {
        if (entity.vehicle != null || entity.busyWithRole() || entity.resupplying || entity.diggedIn ||
            entity.movementLockedByCover() || entity.combatLockedByMedic() || entity.retreatPoint() != null) return false
        val task = task(entity) ?: return false
        val squad = entity.currentSquad() ?: return false
        val state = squad.tactics
        val view = state.snapshot ?: return false
        if (state.plan?.status==TacticalStatus.REGROUPING && state.plan?.pausedSince!=Long.MIN_VALUE && task.job in RUNNING_JOBS) {
            if (task.pausedAt==null) task.pausedAt=entity.level().gameTime
            entity.navigation.stop()
            return true
        }
        task.pausedAt?.let { since ->
            task.lastProgress+=entity.level().gameTime-since
            task.pausedAt=null
            task.nextSearch=entity.level().gameTime
        }
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
            if (TacticalPositions.reaches(path,position)) entity.navigation.moveTo(path,TacticalPositions.speed(task))
            else { task.position = null; task.search = null; return !failedTask(entity,task,"position path no longer reaches destination") }
        }
        return true
    }

    private fun failedTask(entity: NpcEntity,task: TacticalTask,reason: String): Boolean {
        if (++task.failures < if (task.job==TacticalJob.OVERWATCH) 1 else 3) return false
        val plan = entity.currentSquad()?.tactics?.plan ?: return true
        if (plan.pattern in TacticalFlanks.PATTERNS+TacticalPattern.ATTACK_HEIGHT)
            entity.currentSquad()?.tactics?.holdAfterFailure?.set(entity.uuid,entity.level().gameTime+120)
        TacticalManeuvers.abandon(plan,entity.uuid)
        FiringSpots.release(entity.uuid)
        if (DebugFlags.on(LogGroup.ORDER)) DebugFlags.log(LogGroup.ORDER,
            "[tactics] {} plan={} pattern={} job={} reason={} failures={} individual fallback, remaining={}",
            entity.uuid,plan.id,plan.pattern,task.job,reason,task.failures,jobs(plan))
        return true
    }

    private fun jobs(plan: TacticalPlan) = plan.tasks.values.groupingBy { it.job }.eachCount()

    /** Moving groups keep their path; stationary shooters request a new coordinated position. */
    fun repositionForFire(entity: NpcEntity) {
        val task=task(entity) ?: return
        val position=task.position ?: return
        if (task.job !in setOf(TacticalJob.COVER,TacticalJob.ANTI_ARMOUR,TacticalJob.OVERWATCH) || entity.position().distanceTo(position)>2.0) return
        task.position=null
        task.search=null
        task.nextSearch=entity.level().gameTime+10
        FiringSpots.release(entity.uuid)
    }

    fun preferredTarget(entity: NpcEntity, level: ServerLevel): LivingEntity? {
        val task = task(entity) ?: return null
        val view = entity.currentSquad()?.tactics?.snapshot ?: return null
        val visible = view.visible.asSequence().filter { !it.armoured || AntiArmourKit.loaded(entity) }
            .mapNotNull { level.getEntity(it.id) as? LivingEntity }
            .filter { it.isAlive && entity.isEnemy(it) && DetectionSightline.canSee(entity,it) }
            .filter { task.job != TacticalJob.ANTI_ARMOUR || AntiArmourKit.worthARocket(entity,it) }
            .toList()
        val nearest = visible.minOfOrNull { it.position().distanceTo(task.focus ?: entity.position()) } ?: return null
        return FireAllocation.pick(entity,level,visible.filter {
            it.position().distanceTo(task.focus ?: entity.position()) <= nearest+12.0
        }.sortedBy { entity.distanceToSqr(it) })
    }

    fun permitsFire(entity: NpcEntity, target: LivingEntity): Boolean {
        val view = entity.currentSquad()?.tactics?.snapshot ?: return true
        return view.visible.none { it.id == target.uuid && it.armoured } || AntiArmourKit.loaded(entity)
    }

    fun firingLaneEnd(entity: NpcEntity, target: LivingEntity): Vec3 =
        if (entity.currentSquad()?.tactics?.plan?.pattern == TacticalPattern.ENCIRCLE)
            target.eyePosition.add(target.eyePosition.subtract(entity.eyePosition).normalize().scale(24.0)) else target.eyePosition

    private fun average(points: List<Vec3>): Vec3 = Vec3(
        points.map { it.x }.sorted()[points.size/2],points.map { it.y }.sorted()[points.size/2],points.map { it.z }.sorted()[points.size/2])
    private val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
    private val GROUND_ROLES = setOf(NpcClass.RIFLEMAN, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)
    private val RUNNING_JOBS = setOf(TacticalJob.FLANK, TacticalJob.ADVANCE, TacticalJob.REGROUP, TacticalJob.RETREAT)
}
