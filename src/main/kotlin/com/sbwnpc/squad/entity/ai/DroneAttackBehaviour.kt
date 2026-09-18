package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.tools.MillisTimer
import com.atsuishio.superbwarfare.tools.SeekTool
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DroneCombat
import com.sbwnpc.squad.combat.DroneAim
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.ShotBudget
import com.sbwnpc.squad.combat.TargetSelection
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * SBW drones are Entities, not LivingEntities, so they cannot enter vanilla ATTACK_TARGET memory.
 * Defend against a visible hostile drone while otherwise following squad orders. Existing living
 * targets, vehicle duty, cover and healing all take priority; no pathfinding toward airborne drones.
 */
class DroneAttackBehaviour : ExtendedBehaviour<NpcEntity>() {
    init {
        noTimeout()
    }

    private var target: Entity? = null
    private var nextScanTick = 0
    private var aimTime = 0
    private val shootTimer = MillisTimer()

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> =
        listOf(Pair.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT))

    private fun gunData(entity: NpcEntity): GunData? =
        if (entity.mainHandItem.item is GunItem) GunData.from(entity.mainHandItem) else null

    private fun canEngage(entity: NpcEntity): Boolean {
        if (entity.target != null || entity.isPassenger || entity.vehicleTransport ||
            entity.combatLockedByCover() || entity.combatLockedByMedic()) return false
        if (entity.npcClass == NpcClass.MORTAR_OPERATOR || entity.npcClass == NpcClass.MORTAR_LOADER) return false
        val data = gunData(entity) ?: return false
        return data.countBackupAmmo(entity) > 0 || data.hasEnoughAmmoToShoot(entity)
    }

    private fun followRange(entity: NpcEntity): Double =
        entity.getAttribute(Attributes.FOLLOW_RANGE)?.value ?: 48.0

    private fun eligibleTarget(entity: NpcEntity, candidate: Entity): Boolean =
        candidate.level() === entity.level() && DroneCombat.isHostileDrone(entity, candidate) &&
            SeekTool.NOT_IN_SMOKE.test(candidate) &&
            entity.distanceToSqr(candidate) <= followRange(entity).let { it * it }

    private fun validTarget(entity: NpcEntity, candidate: Entity): Boolean =
        eligibleTarget(entity, candidate) && entity.sensing.hasLineOfSight(candidate)

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean {
        if (entity.tickCount < nextScanTick) return false
        nextScanTick = entity.tickCount + SCAN_INTERVAL
        if (!canEngage(entity)) return false
        val candidates = level.getEntities(entity, entity.boundingBox.inflate(followRange(entity))) {
            eligibleTarget(entity, it)
        }
        target = TargetSelection.nearestVisible(candidates, { entity.distanceToSqr(it) }) {
            entity.sensing.hasLineOfSight(it)
        }
        return target != null
    }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean =
        canEngage(entity) && target?.let { validTarget(entity, it) } == true

    override fun start(entity: NpcEntity) {
        entity.aimingAtDrone = true
        entity.isAggressive = true
    }

    override fun stop(entity: NpcEntity) {
        entity.aimingAtDrone = false
        if (entity.target == null) {
            entity.isAggressive = false
            entity.stopUsingItem()
        }
        target = null
        aimTime = 0
        shootTimer.stop()
    }

    override fun tick(entity: NpcEntity) {
        val drone = target ?: return
        if (!canEngage(entity) || !validTarget(entity, drone)) {
            entity.aimingAtDrone = false
            aimTime = 0
            shootTimer.stop()
            return
        }
        val data = gunData(entity) ?: return

        entity.aimingAtDrone = true
        aimTime = minOf(entity.npcRank.aimTimeTicks, aimTime + 1)
        entity.lookAt(drone, 30f, 30f)
        entity.lookControl.setLookAt(drone, 30f, 30f)

        val spread = DroneCombat.spreadForTarget(entity.npcRank.spread * entity.npcClass.accuracyMultiplier, drone)
        // Match Mob.lookAt: non-living drones are aimed at their vertical centre, not eyeY.
        val targetPoint = Vec3(drone.x, if (drone is LivingEntity) drone.eyeY else drone.boundingBox.center.y, drone.z)
        val firingEndpoint = DroneAim.firingEndpoint(entity.eyePosition, entity.lookAngle, targetPoint)
        val clearShot = firingEndpoint != null &&
            FriendlyFireGuard.hasClearLineOfFire(entity, firingEndpoint, spread) &&
            FriendlyFireGuard.hasClearBlastRadius(entity, drone.position(), data.get(GunProp.EXPLOSION_RADIUS))

        // Same lifecycle as ordinary infantry fire; do not skip reload/bolt updates while aiming.
        data.tick(entity, true)
        if (data.shouldStartReloading(entity)) data.startReload()
        if (data.shouldStartBolt()) data.startBolt()

        if (!clearShot || !data.canShoot(entity) || aimTime < entity.npcRank.aimTimeTicks) {
            shootTimer.stop()
            return
        }

        val roundsPerSecond = data.get(GunProp.RPM).toDouble() / 60.0
        var cooldown = Math.round(1000 / roundsPerSecond).coerceAtLeast(1)
        val mode = data.selectedFireModeInfo().mode
        if (mode == FireMode.SEMI || (mode == FireMode.BURST && data.burstAmount.get() == 0)) {
            cooldown += entity.npcRank.semiFireIntervalMs
        }
        if (!shootTimer.started()) {
            shootTimer.start()
            shootTimer.progress = cooldown + 1
        }
        if (shootTimer.progress >= cooldown) {
            shootTimer.progress = ShotBudget.fire(shootTimer.progress, cooldown) {
                data.shoot(entity, spread, false, drone.uuid)
            }
            entity.lastShotTick = entity.tickCount
        }
    }

    private companion object {
        const val SCAN_INTERVAL = 10
    }
}
