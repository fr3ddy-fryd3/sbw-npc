package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.BlindShot
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.IncomingFire
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * The reply to incoming fire whose shooter he cannot see: the `blind_return_fire` rule, an
 * immediate reaction to danger. The bursts at a target lost in foliage are his plan's
 * ([com.sbwnpc.squad.ai.tactics.EngageMove.FOLIAGE_FIRE], #189); this behaviour only keeps the
 * sighting memory they start from ([NpcEntity.lastSeenFire]) up to date, every tick, as before.
 */
class BlindReturnFireBehaviour : ExtendedBehaviour<NpcEntity>() {
    init {
        noTimeout()
    }
    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()
    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.busyWithRole() || entity.vehicle != null || entity.holds(NpcActivity.COVER)) return false
        if (!MandatoryRules.may(MandatoryRule.BLIND_RETURN_FIRE, entity)) return false
        val target = entity.target
        if (target?.let(com.sbwnpc.squad.combat.VehicleTargeting::isGroundArmour) == true) return false
        if (target != null) {
            val visibility = DetectionSightline.trace(entity, target)
            // An aimed gun at a visible target is never joined by a blind reply.
            if (target.isAlive && visibility == DetectionSightline.Visibility.VISIBLE) {
                entity.rememberVisible(target)
                return false
            }
            if (target.isAlive) entity.noticeHidden(target, visibility) else entity.lastSeenFire.cancel()
        }
        return entity.incomingFire.replying(entity.level().gameTime)
    }

    // A hidden recon squad does not fire at a guess (#194); being shot at has already discovered it.
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity) = eligible(entity) && !NativeTactics.holdsFire(entity)
    override fun shouldKeepRunning(entity: NpcEntity) = eligible(entity)
    override fun tick(entity: NpcEntity) {
        val now = entity.level().gameTime
        val aim = entity.incomingFire.point(now) ?: return
        val outcome = BlindShot.fire(entity, aim, IncomingFire.laneEnd(entity.eyePosition, aim), entity.incomingFire, "return-fire")
        if (outcome == BlindShot.Outcome.FIRED) {
            com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.ORDER, "[return-fire] {} shot: aim={} remaining={}", entity.uuid, aim, entity.incomingFire.roundsLeft)
        }
    }
}
