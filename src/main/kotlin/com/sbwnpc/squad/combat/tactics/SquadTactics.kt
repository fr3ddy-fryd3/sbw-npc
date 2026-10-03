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
                target.vehicle?.let { Ports.vehicles.isOperational(it) } == true,
                1.0 + (if (dangerous) 1.0 else 0.0) + (if (target.uuid == squad.focusEntity) 5.0 else 0.0))
        }
        state.contacts.entries.removeIf { now - it.value.seenAt > 200 }
        val members = npcs.map { npc ->
            val target = npc.target
            val gun = Ports.guns.inHand(npc)
            val ready = gun?.hasAmmo() == true && !npc.busyWithRole() && !npc.resupplying && !npc.combatLockedByMedic() && !npc.evadingGrenade()
            TacticalMember(npc.uuid, npc.position(), npc.npcClass, (npc.health / npc.maxHealth).toDouble(), ready,
                ready && !npc.combatLockedByCover() && (gun?.canShoot() == true || npc.tickCount - npc.lastShotTick <= 30) && target != null &&
                    target.uuid in seen && DetectionSightline.canSee(npc, target) && npc.sensing.hasLineOfSight(target),
                npc.isSuppressed(), AntiArmourKit.loaded(npc))
        }
        state.peakStrength = maxOf(state.peakStrength, members.size)
        val view = TacticalSnapshot(now, squad.order, squad.orderStamp, center, entity.homeCenter(), members,
            state.contacts.values.toList(), npcs.mapNotNull { it.incomingFire.point(now) },
            npcs.any { it.evadingGrenade() || GrenadeHazard.threatens(level, it.position()) },
            stalled = npcs.any { it.blockedSightSince?.let { since -> it.tickCount - since >= 80 } == true },
            peakStrength = state.peakStrength)
        state.snapshot = view
        var choice = TacticalRules.choose(view)
        if (choice.pattern == state.blockedPattern && now < state.blockedUntil)
            choice = TacticalChoice(TacticalPattern.REORGANIZE, choice.focus)
        if (state.select(choice, squad.orderStamp, now)) {
            state.plan?.let { plan ->
                TacticalManeuvers.assign(squad.id, plan, view)
                DebugFlags.log(LogGroup.ORDER, "[tactics] %s plan=%d pattern=%s members=%d contacts=%d", squad.name, plan.id, plan.pattern, members.size, view.visible.size)
            }
        }
        state.plan?.let { TacticalManeuvers.advance(squad.id, state, it, view) }
    }

    fun task(entity: NpcEntity): TacticalTask? {
        refresh(entity)
        val squad = entity.currentSquad() ?: return null
        val plan = squad.tactics.plan ?: return null
        if (plan.stamp != squad.orderStamp || plan.status == TacticalStatus.FAILED) return null
        return plan.tasks[entity.uuid]
    }

    fun hasTask(entity: NpcEntity): Boolean = task(entity) != null

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
            when (val result = TacticalPositions.find(entity, task, view)) {
                else -> when (result.outcome) {
                    TacticalPositions.Outcome.DEFERRED -> { task.nextSearch = entity.level().gameTime + 2; return true }
                    TacticalPositions.Outcome.UNREACHABLE -> {
                        task.nextSearch = entity.level().gameTime + 30
                        if (++task.failures >= 3) state.fail(entity.level().gameTime)
                        return true
                    }
                    TacticalPositions.Outcome.FOUND -> {
                        task.position = result.position
                        task.lastProgress = entity.level().gameTime
                        task.nextSearch = entity.level().gameTime + 20
                    }
                }
            }
        }
        val position = task.position ?: return true
        val distance = entity.position().distanceTo(position)
        if (distance < task.closest - 0.5) { task.closest = distance; task.lastProgress = entity.level().gameTime }
        if (distance <= 2.0) { entity.navigation.stop(); return true }
        if (entity.level().gameTime - task.lastProgress >= 100) {
            task.position = null
            task.nextSearch = entity.level().gameTime + 20
            if (++task.failures >= 3) state.fail(entity.level().gameTime)
            return true
        }
        if (entity.navigation.isDone && entity.level().gameTime >= task.nextSearch) {
            task.nextSearch = entity.level().gameTime + 20
            val path = entity.navigation.createPath(position.x, position.y, position.z, 0)
            if (path?.canReach() == true) entity.navigation.moveTo(path, if (task.job in RUNNING_JOBS) 1.3 else 1.0)
            else { task.position = null; task.failures++ }
        }
        return true
    }

    fun preferredTarget(entity: NpcEntity, level: ServerLevel): LivingEntity? {
        val task = task(entity) ?: return null
        val view = entity.currentSquad()?.tactics?.snapshot ?: return null
        return view.visible.asSequence().mapNotNull { level.getEntity(it.id) as? LivingEntity }
            .filter { it.isAlive && entity.isEnemy(it) && DetectionSightline.canSee(entity, it) }
            .filter { task.job != TacticalJob.ANTI_ARMOUR || AntiArmourKit.worthARocket(entity, it) }
            .minByOrNull { it.position().distanceToSqr(task.focus ?: entity.position()) }
    }

    private fun average(points: List<Vec3>): Vec3 = points.fold(Vec3.ZERO) { a, b -> a.add(b) }.scale(1.0 / points.size.coerceAtLeast(1))
    private val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
    private val GROUND_ROLES = setOf(NpcClass.RIFLEMAN, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)
    private val RUNNING_JOBS = setOf(TacticalJob.FLANK, TacticalJob.ADVANCE, TacticalJob.REGROUP, TacticalJob.RETREAT)
}
