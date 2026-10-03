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
        state.contacts.entries.removeIf { now - it.value.seenAt > 200 }
        val members = npcs.map { npc ->
            val target = npc.target
            val gun = Ports.guns.inHand(npc)
            val ready = (gun?.hasAmmo() == true || AntiArmourKit.loaded(npc)) && !npc.busyWithRole() && !npc.resupplying && !npc.combatLockedByMedic() && !npc.evadingGrenade()
            TacticalMember(npc.uuid, npc.position(), npc.npcClass, (npc.health / npc.maxHealth).toDouble(), ready,
                ready && !npc.combatLockedByCover() && (npc.readyToCover || npc.firedRecently(10)) && target != null &&
                    target.uuid in seen && DetectionSightline.canSee(npc, target) && npc.sensing.hasLineOfSight(target),
                npc.isSuppressed(), AntiArmourKit.loaded(npc),
                if (npc.readyToCover && target?.uuid in seen) target?.position() else npc.lastFireAt)
        }
        state.peakStrength = maxOf(state.peakStrength, members.size)
        val knownFocus = state.contacts.values.filter { now - it.seenAt <= 20 }.maxByOrNull { it.priority }?.position
        val terrain = TacticalTerrain.assess(level, npcs, knownFocus ?: entity.homeCenter(), knownFocus)
        val view = TacticalSnapshot(now, squad.order, squad.orderStamp, center, entity.homeCenter(), members,
            state.contacts.values.filter { it.position.distanceTo(center) <= 96.0 }, npcs.mapNotNull { it.incomingFire.point(now) },
            npcs.any { it.evadingGrenade() || GrenadeHazard.threatens(level, it.position()) },
            narrow = terrain.narrow, open = terrain.open,
            stalled = npcs.any { it.blockedSightSince?.let { since -> it.tickCount - since >= 80 } == true },
            peakStrength = state.peakStrength)
        if (squad.order == SquadOrder.DEFEND && state.snapshot?.home != null && view.home != null &&
            state.snapshot!!.home!!.distanceTo(view.home) > 8.0) state.plan?.status = TacticalStatus.COMPLETED
        state.snapshot = view
        var choice = TacticalRules.choose(view)
        if (choice.pattern != TacticalPattern.EVADE && now < (state.blocked[choice.pattern] ?: Long.MIN_VALUE))
            choice = TacticalChoice(if (now < (state.blocked[TacticalPattern.REORGANIZE] ?: Long.MIN_VALUE))
                (if (now < (state.blocked[TacticalPattern.REORIENT] ?: Long.MIN_VALUE)) TacticalPattern.FOLLOW_ORDER else TacticalPattern.REORIENT)
                else TacticalPattern.REORGANIZE,choice.focus)
        val previousTasks = state.plan?.tasks?.toMap().orEmpty()
        val previousStatus = state.plan?.status
        if (state.select(choice, squad.orderStamp, now)) {
            state.plan?.let { plan ->
                TacticalManeuvers.assign(squad.id, plan, view)
                DebugFlags.log(LogGroup.ORDER, "[tactics] {} plan={} pattern={} members={} contacts={}", squad.name, plan.id, plan.pattern, members.size, view.visible.size)
            }
        }
        state.plan?.let { plan ->
            TacticalManeuvers.advance(squad.id,state,plan,view)
            if (plan.status != previousStatus) DebugFlags.log(LogGroup.ORDER,"[tactics] {} plan={} phase={} tasks={}",
                squad.name,plan.id,plan.status,plan.tasks.size)
        }
        for ((id,old) in previousTasks) if (state.plan?.tasks?.get(id) !== old) FiringSpots.release(id)
    }

    fun task(entity: NpcEntity): TacticalTask? {
        refresh(entity)
        val squad = entity.currentSquad() ?: return null
        val plan = squad.tactics.plan ?: return null
        if (plan.stamp != squad.orderStamp || plan.status == TacticalStatus.FAILED) return null
        return plan.tasks[entity.uuid]
    }

    fun hasTask(entity: NpcEntity): Boolean = task(entity) != null

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
            entity.combatLockedByCover() || entity.combatLockedByMedic() || entity.retreatPoint() != null) return false
        val task = task(entity) ?: return false
        val squad = entity.currentSquad() ?: return false
        val state = squad.tactics
        val view = state.snapshot ?: return false
        if (task.job == TacticalJob.WAIT) { entity.navigation.stop(); return true }
        if (task.position == null && entity.level().gameTime >= task.nextSearch) {
            val result = TacticalPositions.find(entity, task, view)
            when (result.outcome) {
                TacticalPositions.Outcome.DEFERRED -> { task.nextSearch = entity.level().gameTime + 2; return true }
                TacticalPositions.Outcome.UNREACHABLE -> {
                    task.nextSearch = entity.level().gameTime + 30
                    DebugFlags.log(LogGroup.ORDER,"[tactics] {} job={} unreachable={} attempt={}",entity.uuid,task.job,task.anchor,task.failures+1)
                    if (++task.failures >= 3) state.fail(entity.level().gameTime)
                    return true
                }
                TacticalPositions.Outcome.FOUND -> {
                    task.position = result.position
                    task.closest = Double.MAX_VALUE
                    task.lastProgress = entity.level().gameTime
                    task.nextSearch = entity.level().gameTime + 20
                }
            }
        }
        val position = task.position ?: return true
        FiringSpots.claim(entity.uuid,position)
        val distance = entity.position().distanceTo(position)
        if (distance < task.closest - 0.5) { task.closest = distance; task.lastProgress = entity.level().gameTime }
        if (distance <= 2.0) {
            if (task.job in RUNNING_JOBS + TacticalJob.SEARCH && entity.position().distanceTo(task.anchor) > 10.0) {
                task.position = null
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
            task.position = null
            task.nextSearch = entity.level().gameTime + 20
            if (++task.failures >= 3) state.fail(entity.level().gameTime)
            return true
        }
        if (entity.navigation.isDone && entity.level().gameTime >= task.nextSearch) {
            task.nextSearch = entity.level().gameTime + 20
            if (!TacticalBudget.path(entity.level().gameTime)) { task.nextSearch = entity.level().gameTime + 2; return true }
            val path = entity.navigation.createPath(position.x, position.y, position.z, 0)
            if (path?.canReach() == true) entity.navigation.moveTo(path, if (task.job in RUNNING_JOBS) 1.3 else 1.0)
            else { task.position = null; if (++task.failures >= 3) state.fail(entity.level().gameTime) }
        }
        return true
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
