package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity
import com.atsuishio.superbwarfare.tools.RangeTool
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** Shared grenade trajectory and friendly-fire gate for regular and reserve throws. */
object GrenadeThrower {
    private const val THROW_SPEED = 1.0
    private const val GRAVITY = 0.05

    fun isSafeToThrow(thrower: NpcEntity, target: Vec3): Boolean =
        FriendlyFireGuard.hasClearLineOfFire(thrower, target) &&
            FriendlyFireGuard.hasClearBlastRadius(thrower, target, ExplosionConfig.M67_GRENADE_EXPLOSION_RADIUS.get().toDouble())

    fun throwAt(thrower: NpcEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3 = Vec3.ZERO) {
        val grenade = HandGrenadeEntity(thrower, level)
        grenade.deltaMovement = RangeTool.calculateFiringSolution(thrower.eyePosition, target, targetVelocity, THROW_SPEED, GRAVITY)
        level.addFreshEntity(grenade)
    }
}
