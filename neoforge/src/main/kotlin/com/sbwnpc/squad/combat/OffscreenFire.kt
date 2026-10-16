package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.HandGun
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import kotlin.math.exp

/**
 * Hitscan stand-in for `GunData.shoot` when nobody can see or hear the shot anyway.
 *
 * Every real SBW shot is a `ProjectileEntity` — its own physics, block/entity collision each tick,
 * and a network-tracked entity for every client in range. A large fight sustains hundreds of them
 * at once, and that is the single biggest external cost of many NPCs. But a fight with no player
 * within [WITNESS_RADIUS] has no audience: no tracer to see, no impact to hear, no one to be hit
 * by a stray round. For those, the shot is resolved right here as a probability roll and a direct
 * `hurt()` — same damage type (so `NpcEntity.hurt`'s suppression reaction still fires exactly as
 * for a real bullet), same ammo/burst bookkeeping as a real shot ([HandGun.spendShot]), no
 * entity, no sound.
 *
 * Only for direct-fire, non-explosive guns ([canSimulate]) — a grenade launcher's splash and
 * terrain damage can't be faked by a single hurt() call, so the M79 keeps firing real rounds.
 *
 * The hit model is deliberately simple and tunable: base chance decays with distance and with the
 * shooter's effective spread (rank x class), clamped to [MIN_HIT_CHANCE, MAX_HIT_CHANCE]. It's
 * meant to land in the same ballpark as what real projectiles achieve against a moving target,
 * not to be exact — the point is that off-screen fights resolve at a similar pace, not a
 * different outcome.
 */
object OffscreenFire {
    const val WITNESS_RADIUS = 128.0

    private const val BASE_HIT_CHANCE = 0.75
    private const val DISTANCE_FALLOFF = 40.0   // e-folding distance in blocks
    private const val SPREAD_DIVISOR = 6.0      // NpcRank.spread is 3..7 -> factor 0.46..0.67
    private const val MIN_HIT_CHANCE = 0.05
    private const val MAX_HIT_CHANCE = 0.6

    /** Any (non-spectator) player close enough to see or hear either end of the shot. */
    fun hasWitness(level: ServerLevel, shooter: NpcEntity, target: LivingEntity): Boolean =
        level.getNearestPlayer(shooter.x, shooter.y, shooter.z, WITNESS_RADIUS, false) != null ||
            level.getNearestPlayer(target.x, target.y, target.z, WITNESS_RADIUS, false) != null

    fun canSimulate(gun: HandGun): Boolean = gun.explosionRadius <= 0.0

    fun hitChance(distance: Double, spread: Double): Double {
        val chance = BASE_HIT_CHANCE * exp(-distance / DISTANCE_FALLOFF) / (1.0 + spread / SPREAD_DIVISOR)
        return chance.coerceIn(MIN_HIT_CHANCE, MAX_HIT_CHANCE)
    }

    /** Resolves one trigger pull. Caller has already checked aim time, line of fire and
     *  `canShoot` — this re-checks the latter only as a guard. */
    fun fire(shooter: NpcEntity, gun: HandGun, target: LivingEntity, spread: Double) {
        if (!gun.canShoot()) return

        val pellets = gun.pellets.coerceAtLeast(1)
        val chance = hitChance(shooter.distanceTo(target).toDouble(), spread)
        var hits = 0
        repeat(pellets) { if (shooter.random.nextDouble() < chance) hits++ }
        if (hits > 0) {
            gun.inflictHit(target, (gun.damage * hits).toFloat())
        }
        gun.spendShot()
    }
}
