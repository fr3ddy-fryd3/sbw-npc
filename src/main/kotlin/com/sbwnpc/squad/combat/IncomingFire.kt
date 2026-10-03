package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3

/** A direction inferred from a hit, never a live reference to an unseen attacker. */
class IncomingFire {
    private var aim: Vec3? = null
    private var expires = Long.MIN_VALUE
    private var lastEpisode = Long.MIN_VALUE
    private var nextShot = 0L
    private var rounds = 0
    private var bursts = 0
    var replyUntil = Long.MIN_VALUE
        private set

    fun record(origin: Vec3, velocity: Vec3?, source: Vec3?, now: Long) {
        val direction = direction(origin, velocity, source) ?: return
        // Quantize the bearing and pitch; a damage source must not become a perfect aim point.
        val yaw = kotlin.math.atan2(direction.z, direction.x)
        val bearing = kotlin.math.round(yaw / 0.15) * 0.15
        val pitch = kotlin.math.round(direction.y.coerceIn(-0.7, 0.7) / 0.12) * 0.12
        aim = origin.add(Vec3(kotlin.math.cos(bearing), pitch, kotlin.math.sin(bearing)).normalize().scale(96.0))
        expires = now + 240
        if (lastEpisode == Long.MIN_VALUE || now - lastEpisode >= 160) {
            lastEpisode = now
            bursts = 2
            rounds = 0
            replyUntil = Long.MIN_VALUE
            nextShot = now
        }
    }

    fun point(now: Long): Vec3? = aim?.takeIf { now < expires }
    fun pending(now: Long): Boolean = point(now) != null && bursts > 0
    fun beginReply(now: Long) {
        if (pending(now)) replyUntil = now + 60
    }
    fun replying(now: Long): Boolean = pending(now) && now < replyUntil
    fun ready(now: Long): Boolean = replying(now) && now >= nextShot
    fun shot(now: Long, interval: Long) {
        if (!ready(now)) return
        rounds++
        nextShot = now + interval.coerceAtLeast(1)
        if (rounds >= 3) {
            rounds = 0
            bursts--
            nextShot = maxOf(nextShot, now + 10)
        }
    }
    fun endReply() { replyUntil = Long.MIN_VALUE }
    fun clear() { aim = null; bursts = 0; endReply() }

    companion object {
        fun direction(origin: Vec3, velocity: Vec3?, source: Vec3?): Vec3? {
            val towardsSource = velocity?.takeIf { it.lengthSqr() > 1.0e-6 }?.scale(-1.0)
                ?: source?.subtract(origin)?.takeIf { it.lengthSqr() > 1.0e-6 }
            return towardsSource?.normalize()
        }
    }
}
