package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity
import com.atsuishio.superbwarfare.tools.RangeTool
import com.sbwnpc.squad.domain.port.Grenades
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

object SbwGrenades : Grenades {
    private const val THROW_SPEED = 1.0
    private const val GRAVITY = 0.05

    // The M67 — what NPCs throw, and what HandGrenadeEntity is.
    override val blastRadius: Double
        get() = ExplosionConfig.M67_GRENADE_EXPLOSION_RADIUS.get().toDouble()

    override fun throwAt(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3) {
        val grenade = HandGrenadeEntity(thrower, level)
        grenade.deltaMovement = RangeTool.calculateFiringSolution(thrower.eyePosition, target, targetVelocity, THROW_SPEED, GRAVITY)
        level.addFreshEntity(grenade)
    }

    // HandGrenadeEntity (the M67, and anything built on it) is the one on a timed fuse. The RGO and
    // launcher rounds go off on contact.
    override fun isTimedGrenade(entity: Entity): Boolean = entity is HandGrenadeEntity
}
