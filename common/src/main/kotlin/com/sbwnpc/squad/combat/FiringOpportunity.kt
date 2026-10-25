package com.sbwnpc.squad.combat

import com.sbwnpc.squad.combat.tactics.TacticalTargetSelection.Opportunity
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

/** A visible known enemy is not necessarily shootable through glass, a hull or an allied firing lane. */
object FiringOpportunity {
    fun assess(entity: NpcEntity, target: LivingEntity): Opportunity {
        if (!DetectionSightline.canSee(entity, target)) return Opportunity.BLOCKED
        val level = entity.level() as ServerLevel
        val terrainClear = if (entity.distanceToSqr(target) > 128.0 * 128.0) {
            !Sightline.blocked(level, entity.eyePosition, target.eyePosition, entity)
        } else {
            entity.sensing.hasLineOfSight(target)
        }
        if (!terrainClear || !WeaponReach.inRange(entity, entity.eyePosition, target.eyePosition)) return Opportunity.VISIBLE
        val spread = DroneCombat.spreadForTarget(entity.spread, target)
        val hulls = Sightline.vehicleHulls(level, AABB(entity.eyePosition, target.eyePosition).inflate(2.0), entity, target)
        if (Sightline.crosses(hulls, entity.eyePosition, target.eyePosition, spread) ||
            Sightline.ownHullInLane(entity, entity.eyePosition, target.eyePosition, spread, target)
        ) {
            return Opportunity.VISIBLE
        }
        val gun = Ports.guns.inHand(entity)
        val safety = FriendlyFireGuard.assess(entity, com.sbwnpc.squad.combat.tactics.SquadTactics.firingLaneEnd(entity, target), spread, target.position(), gun?.explosionRadius ?: 0.0)
        return if (safety.lineClear && safety.blastClear) Opportunity.SHOOTABLE else Opportunity.VISIBLE
    }
}
