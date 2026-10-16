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
    private const val MAX_FLIGHT_TICKS = 100

    /** SBW can return a prediction for an impossible shot; it must fit the real throw speed. */
    internal fun throwVelocity(from: Vec3, target: Vec3, targetVelocity: Vec3): Vec3? {
        val velocity = RangeTool.calculateFiringSolution(from, target, targetVelocity, THROW_SPEED, GRAVITY)
        return velocity.takeIf {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
                kotlin.math.abs(it.length() - THROW_SPEED) <= 0.01
        }
    }

    // The offensive one is SBW's plain hand grenade (HandGrenadeEntity, on a fuse); the defensive
    // one its RGO, which goes off on impact. Both fly with SBW's default 0.05 gravity.
    override val blastRadius: Double
        get() = maxOf(blastRadius(GrenadeKind.OFFENSIVE), blastRadius(GrenadeKind.DEFENSIVE))

    override fun blastRadius(kind: GrenadeKind): Double = when (kind) {
        GrenadeKind.OFFENSIVE -> ExplosionConfig.M67_GRENADE_EXPLOSION_RADIUS.get().toDouble()
        GrenadeKind.DEFENSIVE -> ExplosionConfig.RGO_GRENADE_EXPLOSION_RADIUS.get().toDouble()
    }

    override fun arcClear(
        thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind, nearTarget: Double
    ): Boolean {
        // Flown the way FastThrowableProjectile moves it: from just under the eyes, a step of the
        // current velocity, then gravity (no drag out of water).
        var pos = Vec3(thrower.x, thrower.eyeY - 0.1, thrower.z)
        var velocity = throwVelocity(pos, target, targetVelocity) ?: return false
        val nearSqr = nearTarget * nearTarget
        repeat(MAX_FLIGHT_TICKS) { tick ->
            val predictedTarget = target.add(targetVelocity.scale((tick + 1).toDouble()))
            val next = pos.add(velocity)
            val hit = level.clip(net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, thrower))
            if (hit.type != net.minecraft.world.phys.HitResult.Type.MISS) return hit.location.distanceToSqr(predictedTarget) <= nearSqr
            val bystander = level.getEntities(thrower, net.minecraft.world.phys.AABB(pos, next).inflate(0.3)) {
                it is LivingEntity && it.isAlive && !it.isSpectator && it.vehicle !== thrower &&
                    it.boundingBox.inflate(0.3).clip(pos, next).isPresent && !com.sbwnpc.squad.team.SquadTeams.isHostile(thrower, it)
            }
            if (bystander.isNotEmpty()) return false
            if (next.distanceToSqr(predictedTarget) <= nearSqr) return true
            pos = next
            velocity = velocity.add(0.0, -GRAVITY, 0.0)
        }
        return false
    }

    override fun throwAt(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind) {
        val velocity = throwVelocity(Vec3(thrower.x, thrower.eyeY - 0.1, thrower.z), target, targetVelocity) ?: return
        val grenade = when (kind) {
            GrenadeKind.OFFENSIVE -> HandGrenadeEntity(thrower, level)
            GrenadeKind.DEFENSIVE -> RgoGrenadeEntity(thrower, level)
        }
        grenade.deltaMovement = velocity
        level.addFreshEntity(grenade)
    }

    override fun item(kind: GrenadeKind): net.minecraft.world.item.ItemStack = when (kind) {
        GrenadeKind.OFFENSIVE -> net.minecraft.world.item.ItemStack(com.atsuishio.superbwarfare.init.ModItems.HAND_GRENADE.get())
        GrenadeKind.DEFENSIVE -> net.minecraft.world.item.ItemStack(com.atsuishio.superbwarfare.init.ModItems.RGO_GRENADE.get())
    }

    // HandGrenadeEntity (the M67, and anything built on it) is the one on a timed fuse. The RGO and
    // launcher rounds go off on contact.
    override fun isTimedGrenade(entity: Entity): Boolean = entity is HandGrenadeEntity
}
