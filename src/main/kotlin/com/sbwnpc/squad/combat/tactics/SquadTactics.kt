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
        TacticalRuntimeEvents.bind(squad, level)
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
        val assessment = state.assess(squad.id, view)
        trace(squad, view, assessment.proposed, assessment.effective, assessment.changed)
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
            view.now-plan.phaseSince,plan.bounds,plan.phase == TacticalPhase.OPENING_LANE,(plan.behavior as? CoveredManeuverState)?.laneAttempts ?: 0,
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

    private fun jobs(plan: TacticalPlan) = plan.tasks.values.groupingBy { it.job }.eachCount()

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

    fun move(entity: NpcEntity): Boolean = TacticalMovement.move(entity)
    fun repositionForFire(entity: NpcEntity) = TacticalMovement.repositionForFire(entity)

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

    fun firingLaneEnd(entity: NpcEntity, target: LivingEntity): Vec3 {
        val extension = entity.currentSquad()?.tactics?.plan?.behavior?.firingLaneExtension ?: 0.0
        return target.eyePosition.add(target.eyePosition.subtract(entity.eyePosition).normalize().scale(extension))
    }

    private fun average(points: List<Vec3>): Vec3 = Vec3(
        points.map { it.x }.sorted()[points.size/2],points.map { it.y }.sorted()[points.size/2],points.map { it.z }.sorted()[points.size/2])
    private val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
    private val GROUND_ROLES = setOf(NpcClass.RIFLEMAN, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)
}
