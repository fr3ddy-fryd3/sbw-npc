package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Two short bursts after a personal sighting is lost in vegetation. Hidden coordinates never enter this memory. */
class LastSeenFire {
    private var target: UUID? = null
    private var eye: Vec3? = null
    private var seenAt = Long.MIN_VALUE
    private var armed = false
    private var nextEpisode = Long.MIN_VALUE
    private var expires = Long.MIN_VALUE
    private var nextShot = Long.MIN_VALUE
    var roundsLeft = 0
        private set

    fun observed(id: UUID): Boolean = target == id

    fun observe(id: UUID, point: Vec3, now: Long) {
        target = id
        eye = point
        seenAt = now
        roundsLeft = 0
        armed = now >= nextEpisode
    }

    /** Repeated hidden observations cannot refill a spent episode or move its aim. */
    fun hidden(id: UUID, visibility: DetectionSightline.Visibility, now: Long): Boolean {
        if (target != id || visibility == DetectionSightline.Visibility.VISIBLE) return false
        if (visibility != DetectionSightline.Visibility.FOLIAGE) {
            cancel()
            return false
        }
        if (!armed || now - seenAt >= MEMORY_TICKS) return false
        armed = false
        roundsLeft = BURSTS * ROUNDS_PER_BURST
        nextShot = now
        expires = now + EPISODE_TICKS
        nextEpisode = now + EPISODE_COOLDOWN_TICKS
        return true
    }

    fun point(now: Long): Vec3? = eye?.takeIf { roundsLeft > 0 && now < expires }
    fun ready(now: Long): Boolean = point(now) != null && now >= nextShot

    fun shot(now: Long, interval: Long) {
        if (!ready(now)) return
        roundsLeft--
        nextShot = now + interval.coerceAtLeast(1)
        if (roundsLeft % ROUNDS_PER_BURST == 0) nextShot = maxOf(nextShot, now + BURST_PAUSE_TICKS)
    }

    fun cancel() {
        roundsLeft = 0
        armed = false
    }

    companion object {
        private const val BURSTS = 2
        private const val ROUNDS_PER_BURST = 3
        private const val BURST_PAUSE_TICKS = 10L
        private const val MEMORY_TICKS = 200L
        private const val EPISODE_TICKS = 100L
        private const val EPISODE_COOLDOWN_TICKS = 160L
    }
}
