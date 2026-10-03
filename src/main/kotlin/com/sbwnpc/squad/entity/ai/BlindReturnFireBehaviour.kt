package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/** Only cover's deliberate peek window permits a finite response to an unseen shooter. */
class BlindReturnFireBehaviour : ExtendedBehaviour<NpcEntity>() {
    init { noTimeout() }
    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()
    private fun eligible(entity: NpcEntity): Boolean =
        entity.target?.let { com.sbwnpc.squad.combat.DetectionSightline.canSee(entity, it) } != true &&
        entity.incomingFire.replying(entity.level().gameTime) && !entity.busyWithRole() &&
        !entity.combatLockedByCover() && !entity.combatLockedByMedic() && !entity.resupplying && entity.vehicle == null
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity) = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity) = eligible(entity)
    override fun tick(entity: NpcEntity) {
        val now = entity.level().gameTime
        val aim = entity.incomingFire.point(now) ?: return
        val gun = Ports.guns.inHand(entity) ?: return
        // Uncertain contacts never authorize grenades, rockets, or simulated hits.
        if (gun.explosionRadius > 0.0 || !gun.hasAmmo()) return
        gun.operate()
        entity.lookControl.setLookAt(aim.x, aim.y, aim.z)
        val delta = aim.subtract(entity.eyePosition)
        entity.yRot = Math.toDegrees(kotlin.math.atan2(delta.z, delta.x)).toFloat() - 90f
        entity.xRot = -Math.toDegrees(kotlin.math.atan2(delta.y, delta.horizontalDistance())).toFloat()
        if (!entity.incomingFire.ready(now) || !gun.canShoot()) return
        val level = entity.level() as ServerLevel
        val spread = maxOf(entity.spread, 9.0)
        val hulls = Sightline.vehicleHulls(level, AABB(entity.eyePosition, aim).inflate(2.0), entity, null)
        if (Sightline.blockedBy(level, entity.eyePosition, aim, entity, hulls, spread) ||
            !FriendlyFireGuard.hasClearLineOfFire(entity, aim, spread)) return
        val interval = kotlin.math.ceil(1200.0 / gun.roundsPerMinute.coerceAtLeast(1.0)).toLong() +
            if (gun.needsTriggerReset) kotlin.math.ceil(entity.npcRank.semiFireIntervalMs / 50.0).toLong() else 0L
        gun.shootAt(spread, aim)
        entity.lastShotTick = entity.tickCount
        entity.incomingFire.shot(now, interval)
    }
}
