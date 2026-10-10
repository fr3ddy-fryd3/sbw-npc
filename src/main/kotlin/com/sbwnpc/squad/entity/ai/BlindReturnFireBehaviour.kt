package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/** One executor for finite unseen fire: cover replies take priority over last-seen vegetation bursts. */
class BlindReturnFireBehaviour : ExtendedBehaviour<NpcEntity>() {
    private var nextLogTick = 0
    init {
        noTimeout()
    }
    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()
    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.busyWithRole() || entity.combatLockedByCover() || entity.combatLockedByMedic() ||
            entity.resupplying || entity.vehicle != null || entity.evadingGrenade()
        ) {
            return false
        }
        val now = entity.level().gameTime
        val target = entity.target
        if (target != null) {
            val visibility = DetectionSightline.trace(entity, target)
            if (target.isAlive && visibility == DetectionSightline.Visibility.VISIBLE) {
                entity.rememberVisible(target)
                return false
            }
            if (target.isAlive) entity.noticeHidden(target, visibility) else entity.lastSeenFire.cancel()
        }
        return entity.incomingFire.replying(now) || entity.lastSeenFire.point(now) != null
    }
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity) = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity) = eligible(entity)
    override fun tick(entity: NpcEntity) {
        val now = entity.level().gameTime
        val incoming = entity.incomingFire.replying(now)
        val aim = (if (incoming) entity.incomingFire.point(now) else entity.lastSeenFire.point(now)) ?: return
        val source = if (incoming) "return-fire" else "foliage-fire"
        com.sbwnpc.squad.combat.AntiArmourKit.wield(entity, launcher = false)
        val gun = Ports.guns.inHand(entity) ?: return
        // Uncertain contacts never authorize grenades, rockets, or simulated hits.
        if (gun.explosionRadius > 0.0 || !gun.hasAmmo()) return
        gun.operate()
        if (!SidearmKit.inRange(entity, entity.eyePosition, aim)) return
        entity.lookControl.setLookAt(aim.x, aim.y, aim.z)
        val delta = aim.subtract(entity.eyePosition)
        entity.yRot = Math.toDegrees(kotlin.math.atan2(delta.z, delta.x)).toFloat() - 90f
        entity.xRot = -Math.toDegrees(kotlin.math.atan2(delta.y, delta.horizontalDistance())).toFloat()
        val ready = if (incoming) entity.incomingFire.ready(now) else entity.lastSeenFire.ready(now)
        if (!ready || !gun.canShoot()) return
        val level = entity.level() as ServerLevel
        val spread = maxOf(entity.spread, 9.0)
        val hulls = Sightline.vehicleHulls(level, AABB(entity.eyePosition, aim).inflate(2.0), entity, null)
        val terrainBlocked = Sightline.blockedBy(
            level,
            entity.eyePosition,
            if (incoming) com.sbwnpc.squad.combat.IncomingFire.laneEnd(entity.eyePosition, aim) else aim,
            entity,
            hulls,
            spread,
        )
        val allyBlocked = !FriendlyFireGuard.hasClearLineOfFire(entity, aim, spread)
        if (terrainBlocked || allyBlocked) {
            if (entity.tickCount >= nextLogTick) {
                nextLogTick = entity.tickCount + 20
                com.sbwnpc.squad.combat.DebugFlags.log(
                    com.sbwnpc.squad.combat.LogGroup.ORDER,
                    "[{}] {} held: terrain={} allies={}",
                    source,
                    entity.uuid,
                    terrainBlocked,
                    allyBlocked,
                )
            }
            return
        }
        val normalInterval = (
            kotlin.math.ceil(1200.0 / gun.roundsPerMinute.coerceAtLeast(1.0)).toLong() +
                if (gun.needsTriggerReset) kotlin.math.ceil(entity.npcRank.semiFireIntervalMs / 50.0).toLong() else 0L
            ) * 50
        val interval = entity.fireIntervalMillis(gun, normalInterval) / 50
        gun.shootAt(spread, aim)
        // SBW's hearing hook excludes NPCs; every NPC firing mode must publish its own noise.
        Hearing.gunshot(level, entity, gun.hearingRadius)
        entity.lastShotTick = entity.tickCount
        if (incoming) {
            entity.incomingFire.shot(now, interval)
        } else {
            entity.lastSeenFire.shot(now, interval)
            entity.lastFireAt = aim
        }
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.ORDER,
            "[{}] {} shot: aim={} remaining={}",
            source,
            entity.uuid,
            aim,
            if (incoming) entity.incomingFire.roundsLeft else entity.lastSeenFire.roundsLeft,
        )
    }
}
