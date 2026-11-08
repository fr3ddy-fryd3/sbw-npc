package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity
import com.atsuishio.superbwarfare.entity.projectile.M18SmokeGrenadeEntity
import com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.RangeTool
import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Grenades
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

@Suppress("TooManyFunctions")
object SbwGrenades : Grenades {
    private const val THROW_SPEED = 1.0
    private const val GRAVITY = 0.05
    private const val MAX_FLIGHT_TICKS = 100
    private const val AIR_DRAG = 0.98
    private const val MAX_AIM_HEIGHT = 4
    private enum class Flight { CLEAR, BLOCKED, HULL }

    /** A thrown smoke grenade starts smoking about when it has landed. */
    private const val SMOKE_FUSE_TICKS = 20

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

    override fun blastRadius(kind: GrenadeKind): Double =
        when (kind) {
            GrenadeKind.OFFENSIVE -> ExplosionConfig.M67_GRENADE_EXPLOSION_RADIUS.get().toDouble()
            GrenadeKind.DEFENSIVE -> ExplosionConfig.RGO_GRENADE_EXPLOSION_RADIUS.get().toDouble()
        }

    override fun landing(
        thrower: LivingEntity,
        level: ServerLevel,
        target: Vec3,
        targetVelocity: Vec3,
        kind: GrenadeKind,
        nearTarget: Double,
        lobs: Boolean,
    ): Vec3? = clearThrow(thrower, level, target, targetVelocity, kind, nearTarget, lobs)?.landing

    /** A throw that reaches its target: the velocity to give the grenade and where it first strikes. */
    private class Throw(val velocity: Vec3, val landing: Vec3)

    private class Flown(val verdict: Flight, val landing: Vec3? = null)

    /** Tries SBW's ordinary throw first, then, with [lobs], higher trajectories over nearby cover. */
    @Suppress("LongParameterList")
    private fun clearThrow(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind, nearTarget: Double, lobs: Boolean): Throw? {
        val from = Vec3(thrower.x, thrower.eyeY - 0.1, thrower.z)
        for (height in 0..(if (lobs) MAX_AIM_HEIGHT else 0)) {
            val velocity = throwVelocity(from, target.add(0.0, height.toDouble(), 0.0), targetVelocity) ?: continue
            val flown = fly(thrower, level, target, targetVelocity, kind, nearTarget, from, velocity, height > 0)
            when (flown.verdict) {
                Flight.CLEAR -> return Throw(velocity, flown.landing ?: return null)
                Flight.HULL -> return null
                Flight.BLOCKED -> Unit
            }
        }
        return null
    }

    /**
     * Flies the grenade to its first strike. The ordinary throw counts once it passes near the
     * target, as it always has. A lob is aimed above the target and flies on past it, so only a
     * strike near the target counts: an RGO lobbed 2 blocks high at 8 blocks would land at 15.
     * The strike is reported either way, for the friendly blast check at the real burst point.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
    private fun fly(
        thrower: LivingEntity,
        level: ServerLevel,
        target: Vec3,
        targetVelocity: Vec3,
        kind: GrenadeKind,
        nearTarget: Double,
        from: Vec3,
        initialVelocity: Vec3,
        lob: Boolean,
    ): Flown {
        // SBW's bouncing grenade applies drag before moving; its impact grenade does not.
        var pos = from
        var velocity = initialVelocity
        val nearSqr = nearTarget * nearTarget
        var passedNear = false
        repeat(MAX_FLIGHT_TICKS) { tick ->
            if (kind == GrenadeKind.OFFENSIVE) velocity = velocity.add(0.0, -GRAVITY, 0.0).scale(AIR_DRAG)
            val predictedTarget = target.add(targetVelocity.scale((tick + 1).toDouble()))
            val next = pos.add(velocity)
            fun struck(at: Vec3, hull: Boolean): Flown =
                when {
                    passedNear || at.distanceToSqr(predictedTarget) <= nearSqr -> Flown(Flight.CLEAR, at)
                    hull -> Flown(Flight.HULL)
                    else -> Flown(Flight.BLOCKED)
                }
            val hit = level.clip(net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, thrower))
            val block = hit.location.takeIf { hit.type != net.minecraft.world.phys.HitResult.Type.MISS }
            // A hull stops a grenade like a wall; it used to fly on through, and one meant for a
            // zombie past a squad's LAV could drop at the thrower's feet (in-game 25.10).
            val hull = level.getEntitiesOfClass(VehicleEntity::class.java, net.minecraft.world.phys.AABB(pos, next).inflate(1.0)) { it !== thrower.rootVehicle }
                .mapNotNull { it.boundingBox.clip(pos, next).orElse(null) }
                .minByOrNull { it.distanceToSqr(pos) }
            val bodies = level.getEntities(thrower, net.minecraft.world.phys.AABB(pos, next).inflate(0.3)) {
                it is LivingEntity && it.isAlive && !it.isSpectator && it.vehicle !== thrower
            }.mapNotNull { body -> body.boundingBox.inflate(0.3).clip(pos, next).orElse(null)?.let { body to it } }
            val ally = bodies.filter { !com.sbwnpc.squad.team.SquadTeams.isHostile(thrower, it.first) }.minOfOrNull { it.second.distanceToSqr(pos) }
            // An RGO goes off on the enemy's body; an M67 bounces off and keeps its fuse.
            val enemy = if (kind == GrenadeKind.DEFENSIVE) {
                bodies.filter { com.sbwnpc.squad.team.SquadTeams.isHostile(thrower, it.first) }.map { it.second }.minByOrNull { it.distanceToSqr(pos) }
            } else {
                null
            }
            val first = listOfNotNull(block?.let { it to false }, hull?.let { it to true }, enemy?.let { it to false })
                .minByOrNull { it.first.distanceToSqr(pos) }
            if (ally != null && (first == null || ally <= first.first.distanceToSqr(pos))) return Flown(Flight.BLOCKED)
            if (first != null) return struck(first.first, first.second)
            if (!lob && next.distanceToSqr(predictedTarget) <= nearSqr) passedNear = true
            pos = next
            if (kind == GrenadeKind.DEFENSIVE) velocity = velocity.add(0.0, -GRAVITY, 0.0)
        }
        return if (passedNear) Flown(Flight.CLEAR, pos) else Flown(Flight.BLOCKED)
    }

    override fun throwAt(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind, nearTarget: Double): Boolean {
        val velocity = clearThrow(thrower, level, target, targetVelocity, kind, nearTarget, lobs = true)?.velocity ?: return false
        val grenade = when (kind) {
            GrenadeKind.OFFENSIVE -> HandGrenadeEntity(thrower, level)
            GrenadeKind.DEFENSIVE -> RgoGrenadeEntity(thrower, level)
        }
        grenade.deltaMovement = velocity
        return level.addFreshEntity(grenade)
    }

    override fun throwSmoke(thrower: LivingEntity, level: ServerLevel, target: Vec3) {
        val velocity = throwVelocity(Vec3(thrower.x, thrower.eyeY - 0.1, thrower.z), target, Vec3.ZERO) ?: return
        val grenade = M18SmokeGrenadeEntity(thrower, level, SMOKE_FUSE_TICKS)
        grenade.deltaMovement = velocity
        level.addFreshEntity(grenade)
    }

    // The cloud is the grenade itself: once its fuse (the constructor's last argument) has run out
    // it puffs smoke where it lies until SBW discards it at 200 ticks. The SmokeDecoyEntity pieces it
    // also lays are decoys for locked-on weapons and show no smoke.
    override fun isSmokeCloud(entity: Entity): Boolean = entity is M18SmokeGrenadeEntity

    override fun smokeIsOut(entity: Entity): Boolean = entity.isAlive && smokeStarted(entity)

    override fun smokeStarted(entity: Entity): Boolean = entity is M18SmokeGrenadeEntity && entity.tickCount >= SMOKE_FUSE_TICKS

    override fun item(kind: GrenadeKind): net.minecraft.world.item.ItemStack =
        when (kind) {
            GrenadeKind.OFFENSIVE -> net.minecraft.world.item.ItemStack(com.atsuishio.superbwarfare.init.ModItems.HAND_GRENADE.get())
            GrenadeKind.DEFENSIVE -> net.minecraft.world.item.ItemStack(com.atsuishio.superbwarfare.init.ModItems.RGO_GRENADE.get())
        }

    // HandGrenadeEntity (the M67, and anything built on it) is the one on a timed fuse. The RGO and
    // launcher rounds go off on contact.
    override fun isTimedGrenade(entity: Entity): Boolean = entity is HandGrenadeEntity
}
