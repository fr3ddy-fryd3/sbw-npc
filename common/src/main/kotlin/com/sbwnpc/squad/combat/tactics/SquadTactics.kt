package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.domain.port.Mobility
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
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

    fun refresh(entity: NpcEntity) = com.sbwnpc.squad.ai.integration.NativeTactics.refresh(entity)

    fun task(entity: NpcEntity) = com.sbwnpc.squad.ai.integration.NativeTactics.assignment(entity)
    fun hasTask(entity: NpcEntity): Boolean = task(entity) != null

    fun equip(entity: NpcEntity) {
        if (entity.vehicle != null || entity.busyWithRole() || entity.holdsAny(NpcActivity.HEAL, NpcActivity.RESUPPLY)) return
        val task = task(entity) ?: return
        val target = entity.target ?: return
        if (task.job == TacticalJob.ANTI_ARMOUR && AntiArmourKit.loaded(entity) &&
            AntiArmourKit.worthARocket(entity, target) && DetectionSightline.canSee(entity, target)
        ) {
            AntiArmourKit.wield(entity, true)
        }
    }

    fun move(entity: NpcEntity): Boolean = com.sbwnpc.squad.ai.integration.NativeTactics.ownsMovement(entity)
    fun repositionForFire(entity: NpcEntity) = com.sbwnpc.squad.ai.integration.NativeTactics.reposition(entity)

    /** Runs the HTN planning this NPC takes part in, once per tick for its squad and above. */
    fun tick(entity: NpcEntity) = com.sbwnpc.squad.ai.integration.NativeTactics.tick(entity)

    /** Whether the NPC's current post action aims its head, so idle looking must not. */
    fun ownsLook(entity: NpcEntity): Boolean = com.sbwnpc.squad.ai.integration.NativeTactics.ownsLook(entity)

    /** The squad's shared combat picture: its members, contacts and incoming fire. */
    fun snapshot(entity: NpcEntity): TacticalSnapshot? = com.sbwnpc.squad.ai.integration.NativeTactics.snapshot(entity)

    /** Whether the NPC's squad fights in an operation together with other squads. */
    fun coordinated(entity: NpcEntity): Boolean = com.sbwnpc.squad.ai.integration.NativeTactics.coordinated(entity)

    /** The operation [squad] takes part in, if any. */
    fun operationOf(level: ServerLevel, squad: java.util.UUID): com.sbwnpc.squad.ai.coordination.OperationView? = com.sbwnpc.squad.ai.integration.NativeTactics.operationOf(level, squad)

    /** Notes the death of a possible objective target; [cancelled] is read later, see [com.sbwnpc.squad.ai.integration.NativeTactics.targetDeath]. */
    fun targetDeath(entity: LivingEntity, cancelled: () -> Boolean) = com.sbwnpc.squad.ai.integration.NativeTactics.targetDeath(entity, cancelled)

    /** Drops every world's tactical state when the server stops. */
    fun clearAll() = com.sbwnpc.squad.ai.integration.NativeTactics.clearAll()

    fun preferredTarget(entity: NpcEntity, level: ServerLevel, probeCursor: Int = 0, reconsiderCurrent: Boolean = false): LivingEntity? {
        val task = task(entity)
        val view = snapshot(entity) ?: return null
        val known = TacticalTargets.candidates(view, null, task?.focus ?: entity.position(), AntiArmourKit.loaded(entity)).asSequence()
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
            allocate = { FireAllocation.pick(entity, level, it.sortedBy(entity::distanceToSqr)) },
            reconsiderCurrent = reconsiderCurrent,
        )
    }

    fun permitsFire(entity: NpcEntity, target: LivingEntity): Boolean {
        val view = snapshot(entity) ?: return true
        return view.visible.none { it.id == target.uuid && it.armoured } || AntiArmourKit.loaded(entity)
    }

    fun firingLaneEnd(entity: NpcEntity, target: LivingEntity): Vec3 = firingLaneEnd(entity, target.eyePosition)

    /** Candidate posts use their own eye origin, with the same safety lane as actual shots. */
    fun firingLaneEnd(entity: NpcEntity, targetEye: Vec3, from: Vec3 = entity.eyePosition): Vec3 {
        val extension = if (coordinated(entity)) 256.0 else 0.0
        return targetEye.add(targetEye.subtract(from).normalize().scale(extension))
    }

    private val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
    private val GROUND_ROLES = setOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)
}
