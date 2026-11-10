package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * One round at a point he cannot see, for the two kinds of fire at a guess: the reply to incoming
 * fire (the `blind_return_fire` rule) and the bursts at a target lost in foliage (his plan's engage).
 * What each episode allows, how many rounds and how fast, is the episode's; this only aims and
 * fires a real round, with every safety check the aimed gun has.
 */
object BlindShot {
    /** What one tick of blind fire did. */
    enum class Outcome {
        /** A round left the gun. */
        FIRED,

        /** Aimed, waiting for the episode's or the gun's next shot. */
        WAITING,

        /** Terrain, a hull or an ally stands in the lane: no round. */
        BLOCKED,

        /** Out of reach or with no gun in hand: nothing aimed. */
        OUT_OF_REACH,

        /** Armour in the bearing, an explosive weapon or an empty one: this episode can never fire. */
        REFUSED,
    }

    /** One blind-fire episode's bookkeeping, as [IncomingFire] and [LastSeenFire] keep it. */
    interface Episode {
        fun ready(now: Long): Boolean

        fun shot(now: Long, interval: Long)
    }

    /**
     * Aims [entity] at [aim] and fires once if [episode] is ready and the lane to [laneEnd] is clear.
     * [source] names the episode in the log. Uncertain contacts never authorize grenades, rockets or
     * simulated hits, so a launcher in hand refuses rather than switching mid-episode.
     */
    fun fire(entity: NpcEntity, aim: Vec3, laneEnd: Vec3, episode: Episode, source: String): Outcome {
        val now = entity.level().gameTime
        if (ArmourFireSafety.bearingContainsArmour(entity, aim)) return Outcome.REFUSED
        AntiArmourKit.wield(entity, launcher = false)
        val gun = Ports.guns.inHand(entity) ?: return Outcome.OUT_OF_REACH
        if (gun.explosionRadius > 0.0 || !gun.hasAmmo()) return Outcome.REFUSED
        gun.operate()
        if (!WeaponReach.inRange(entity, entity.eyePosition, aim)) return Outcome.OUT_OF_REACH
        entity.lookControl.setLookAt(aim.x, aim.y, aim.z)
        val delta = aim.subtract(entity.eyePosition)
        entity.yRot = Math.toDegrees(kotlin.math.atan2(delta.z, delta.x)).toFloat() - YAW_OFFSET
        entity.xRot = -Math.toDegrees(kotlin.math.atan2(delta.y, delta.horizontalDistance())).toFloat()
        if (!episode.ready(now) || !gun.canShoot()) return Outcome.WAITING
        val level = entity.level() as ServerLevel
        val spread = maxOf(entity.spread, MIN_SPREAD)
        val hulls = Sightline.vehicleHulls(level, AABB(entity.eyePosition, aim).inflate(2.0), entity, null)
        val terrainBlocked = Sightline.blockedBy(level, entity.eyePosition, laneEnd, entity, hulls)
        val allyBlocked = !FriendlyFireGuard.hasClearLineOfFire(entity, aim, spread)
        if (terrainBlocked || allyBlocked) {
            if (entity.tickCount % LOG_EVERY_TICKS == 0) {
                DebugFlags.log(LogGroup.ORDER, "[{}] {} held: terrain={} allies={}", source, entity.uuid, terrainBlocked, allyBlocked)
            }
            return Outcome.BLOCKED
        }
        // Ticks between rounds at the gun's rate (20 ticks a second), plus a semi-automatic's trigger reset.
        val normalInterval = (
            kotlin.math.ceil(TICKS_PER_MINUTE / gun.roundsPerMinute.coerceAtLeast(1.0)).toLong() +
                if (gun.needsTriggerReset) kotlin.math.ceil(entity.npcRank.semiFireIntervalMs / MS_PER_TICK.toDouble()).toLong() else 0L
            ) * MS_PER_TICK
        val interval = entity.fireIntervalMillis(gun, normalInterval) / MS_PER_TICK
        gun.shootAt(spread, aim)
        // SBW's hearing hook excludes NPCs; every NPC firing mode must publish its own noise.
        Hearing.gunshot(level, entity, gun.hearingRadius, gun.suppressed)
        entity.lastShotTick = entity.tickCount
        episode.shot(now, interval)
        return Outcome.FIRED
    }

    private const val MIN_SPREAD = 9.0
    private const val YAW_OFFSET = 90f
    private const val TICKS_PER_MINUTE = 1200.0
    private const val MS_PER_TICK = 50L
    private const val LOG_EVERY_TICKS = 20
}
