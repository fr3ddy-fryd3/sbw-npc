package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity
import com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity
import com.sbwnpc.squad.domain.port.GrenadeKind
import com.atsuishio.superbwarfare.tools.RangeTool
import com.sbwnpc.squad.domain.port.Grenades
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

object SbwGrenades : Grenades {
    private const val THROW_SPEED = 1.0
    private const val GRAVITY = 0.05

    // The offensive one is SBW's plain hand grenade (HandGrenadeEntity, on a fuse); the defensive
    // one its RGO, which goes off on impact. Both fly with SBW's default 0.05 gravity.
    override val blastRadius: Double
        get() = maxOf(blastRadius(GrenadeKind.OFFENSIVE), blastRadius(GrenadeKind.DEFENSIVE))

    override fun blastRadius(kind: GrenadeKind): Double = when (kind) {
        GrenadeKind.OFFENSIVE -> ExplosionConfig.M67_GRENADE_EXPLOSION_RADIUS.get().toDouble()
        GrenadeKind.DEFENSIVE -> ExplosionConfig.RGO_GRENADE_EXPLOSION_RADIUS.get().toDouble()
    }

    override fun throwAt(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind) {
        val grenade = when (kind) {
            GrenadeKind.OFFENSIVE -> HandGrenadeEntity(thrower, level)
            GrenadeKind.DEFENSIVE -> RgoGrenadeEntity(thrower, level)
        }
        grenade.deltaMovement = RangeTool.calculateFiringSolution(thrower.eyePosition, target, targetVelocity, THROW_SPEED, GRAVITY)
        level.addFreshEntity(grenade)
    }

    // HandGrenadeEntity (the M67, and anything built on it) is the one on a timed fuse. The RGO and
    // launcher rounds go off on contact.
    override fun isTimedGrenade(entity: Entity): Boolean = entity is HandGrenadeEntity
}
