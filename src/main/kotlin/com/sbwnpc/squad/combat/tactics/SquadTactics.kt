package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.domain.port.Mobility
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

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
        state.contacts[target.uuid] = TacticalContact(
            id = target.uuid,
            position = target.position(),
            seenAt = now,
            velocity = velocity,
            armoured = target.vehicle?.let {
                Ports.vehicles.isOperational(it) &&
                    (
                        Ports.vehicles.modelOf(it) is TankModel ||
                            Ports.vehicles.modelOf(it) is TransportVehicle
                        )
            } == true,
            priority = 1.0 + (if (dangerous) 1.0 else 0.0) + (if (target.uuid == squad.focusEntity) 5.0 else 0.0),
            airborne = target.vehicle?.let { Ports.vehicles.mobility(it) == Mobility.AIR } == true,
        )
        // Discovery is shared even before this observer can start its gun behavior.
        TeamAwareness.report(squad.faction, target.uuid, target.position(), now, "${npc.npcClass} ${npc.uuid.toString().take(8)}")
    }

    fun refresh(entity: NpcEntity) {
        val squad = entity.currentSquad() ?: return
        val level = entity.level() as? ServerLevel ?: return
        val state = squad.tactics
        TacticalRuntimeEvents.bind(squad, level)
        val now = level.gameTime
        if (now < state.nextAssessment && state.plan?.stamp == squad.orderStamp) return
        state.nextAssessment = now + 10
        val npcs = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }
            .filter { it.isAlive && it.vehicle == null && it.npcClass in GROUND_ROLES }
        if (npcs.isEmpty()) {
            state.deactivate(now, "no_ground_members")
            return
        }
        if (state.flankSide == 0.0) state.flankSide = if (squad.id.leastSignificantBits and 1L == 0L) 1.0 else -1.0
        var view = TacticalSnapshotCollector.collect(squad, level, npcs, entity)
        if (AttackAmmoWithdrawal.apply(squad, level, view)) {
            view = TacticalSnapshotCollector.collect(squad, level, npcs, entity)
        }
        view = view.copy(attackAssignment = com.sbwnpc.squad.combat.tactics.operation.AttackOperations.assignment(level, squad))
        val assessment = state.assess(squad.id, view)
        trace(squad, view, assessment.proposed, assessment.effective, assessment.changed)
    }

    /** One heartbeat per five seconds, plus actual plan/phase changes; no per-tick log scans. */
    private fun trace(squad: Squad, view: TacticalSnapshot, proposed: TacticalChoice, effective: TacticalChoice, changed: Boolean) {
        if (!DebugFlags.on(LogGroup.ORDER)) return
        val state = squad.tactics
        if (!changed && view.now < state.nextTrace) return
        val plan = state.plan ?: return
        state.nextTrace = view.now + 100
        DebugFlags.log(
            LogGroup.ORDER,
            SNAPSHOT_FORMAT,
            squad.name,
            squad.id,
            view.now,
            plan.id,
            plan.pattern,
            plan.behavior.javaClass.simpleName,
            plan.phase,
            plan.status,
            plan.reason,
            proposed.pattern,
            proposed.reason,
            effective.pattern,
            effective.reason == TacticalReason.PATTERN_COOLDOWN,
            view.order,
            squad.attackAmmoPolicy,
            view.stamp,
            view.members.size,
            view.peakStrength,
            view.fighting.size,
            view.visible.size,
            view.contacts.size,
            view.incoming.size,
            view.members.count { it.suppressed },
            view.narrow,
            view.open,
            view.stalled,
            plan.focus?.y?.minus(view.center.y),
            TacticalTelemetry.cover(plan, view),
            view.members.count { it.recentFire },
            view.now - plan.lastCover,
            view.now - plan.phaseSince,
            plan.behavior.lifetimeElapsed(plan.started, view.now),
            plan.bounds,
            plan.phase == TacticalPhase.OPENING_LANE,
            (plan.behavior as? CoveredManeuverState)?.laneAttempts ?: 0,
            plan.tasks.values.count { it.position != null },
            plan.failedMembers.size,
            plan.tasks.values.count { it.localFireRecovery },
            jobs(plan),
            TacticalTelemetry.front(plan, view),
        )
        view.attackAssignment?.let {
            DebugFlags.log(LogGroup.ORDER, "[operation] squad={} id={} role={} stage={} revision={} waypoint={} sector={} deployed={} blocked={} externalCover={}", squad.id, it.operation, it.role, it.stage, it.revision, it.waypoint, it.sector, it.deployed, it.blocked, it.externalCover)
        }
    }

    fun task(entity: NpcEntity): TacticalTask? {
        refresh(entity)
        val squad = entity.currentSquad() ?: return null
        val plan = squad.tactics.plan ?: return null
        if (plan.stamp != squad.orderStamp || plan.status == TacticalStatus.FAILED) return null
        return plan.tasks[entity.uuid]
    }

    fun hasTask(entity: NpcEntity): Boolean = task(entity) != null

    /** Unassigned attack members use the same front instead of an independent wedge around their target. */
    fun frontPost(entity: NpcEntity): TacticalFrontPost? {
        val squad = entity.currentSquad() ?: return null
        val view = squad.tactics.snapshot ?: return null
        val member = view.members.firstOrNull { it.id == entity.uuid } ?: return null
        if (!view.offensive || squad.tactics.plan?.behavior?.usesAttackFront(member) == false) return null
        val frontage = squad.tactics.frontage
        frontage.observe(view)
        return frontage.post(entity.uuid, view.center, entity.position())
    }

    private fun jobs(plan: TacticalPlan) = plan.tasks.values.groupingBy { it.job }.eachCount()

    /** A failed combat route yields local firing-position work, rather than a frontal assault. */
    fun holdsAfterFailure(entity: NpcEntity): Boolean {
        val squad = entity.currentSquad() ?: return false
        return squad.tactics.holdsAfterFailure(entity.uuid, squad.orderStamp, entity.level().gameTime)
    }

    fun equip(entity: NpcEntity) {
        if (entity.vehicle != null || entity.busyWithRole() || entity.combatLockedByMedic() || entity.resupplying) return
        val task = task(entity) ?: return
        val target = entity.target ?: return
        if (task.job == TacticalJob.ANTI_ARMOUR && AntiArmourKit.loaded(entity) &&
            AntiArmourKit.worthARocket(entity, target) && DetectionSightline.canSee(entity, target)
        ) {
            AntiArmourKit.wield(entity, true)
        }
    }

    fun move(entity: NpcEntity): Boolean = TacticalMovement.move(entity)
    fun repositionForFire(entity: NpcEntity) = TacticalMovement.repositionForFire(entity)

    fun preferredTarget(entity: NpcEntity, level: ServerLevel, probeCursor: Int = 0): LivingEntity? {
        val task = task(entity)
        val view = entity.currentSquad()?.tactics?.snapshot ?: return null
        val known = TacticalTargets.candidates(view, task, entity.position(), AntiArmourKit.loaded(entity)).asSequence()
            .mapNotNull { level.getEntity(it.id) as? LivingEntity }
            .filter { it.isAlive && entity.isEnemy(it) }
            .filter { task?.job != TacticalJob.ANTI_ARMOUR || AntiArmourKit.worthARocket(entity, it) }
            .toList()
        if (known.isEmpty()) return null
        return TacticalTargetSelection.choose(
            known,
            entity.target,
            probeCursor,
            assess = { FiringOpportunity.assess(entity, it) },
            allocate = { FireAllocation.pick(entity, level, it) },
        )
    }

    fun permitsFire(entity: NpcEntity, target: LivingEntity): Boolean {
        val view = entity.currentSquad()?.tactics?.snapshot ?: return true
        return view.visible.none { it.id == target.uuid && it.armoured } || AntiArmourKit.loaded(entity)
    }

    fun firingLaneEnd(entity: NpcEntity, target: LivingEntity): Vec3 {
        val extension = entity.currentSquad()?.tactics?.plan?.behavior?.firingLaneExtension ?: 0.0
        return target.eyePosition.add(target.eyePosition.subtract(entity.eyePosition).normalize().scale(extension))
    }

    private const val SNAPSHOT_FORMAT = "[tactics] {} snapshot squad={} tick={} plan={} pattern={} state={} statePhase={} phase={} reason={} " +
        "proposed={} proposedReason={} effective={} cooldown={} order={} ammoPolicy={} stamp={} members={} peak={} fighting={} " +
        "visible={} remembered={} incoming={} suppressed={} narrow={} open={} stalled={} heightDelta={} cover={} " +
        "shootersLast40Ticks={} coverAge={} phaseAge={} lifetimeElapsed={} bounds={} openingLane={} laneAttempts={} positioned={} failed={} " +
        "recoveringFirePosts={} jobs={} front={}"

    private val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
    private val GROUND_ROLES = setOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)
}
