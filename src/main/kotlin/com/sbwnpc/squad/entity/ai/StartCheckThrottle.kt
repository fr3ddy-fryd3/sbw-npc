package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity

/**
 * SmartBrainLib calls `checkExtraStartConditions` of every STOPPED behaviour in the active
 * activities on every brain tick (confirmed via javap — there's no vanilla-GoalSelector-style
 * alternate-tick stagger, and `ExtendedBehaviour.cooldownFor` only kicks in after a `stop()`, not
 * before the first start). For behaviours whose eligibility is a handful of lookups that can't
 * change faster than a few ticks (squad/home/order/distance, "is there a mortar around"), this
 * remembers the last answer for [intervalTicks] and only re-evaluates after that.
 *
 * Only for START checks — a running behaviour's `shouldKeepRunning` stays exact, since that's
 * what makes it let go promptly when combat starts or an order changes.
 */
class StartCheckThrottle(private val intervalTicks: Int) {
    private var nextTick = 0
    private var last = false

    inline fun check(entity: NpcEntity, compute: () -> Boolean): Boolean {
        if (!due(entity)) return cached()
        return remember(entity, compute())
    }

    @PublishedApi internal fun due(entity: NpcEntity): Boolean = entity.tickCount >= nextTick
    @PublishedApi internal fun cached(): Boolean = last
    @PublishedApi internal fun remember(entity: NpcEntity, value: Boolean): Boolean {
        // Small jitter so a squad deployed on one tick doesn't re-check in lockstep forever.
        nextTick = entity.tickCount + intervalTicks + entity.random.nextInt(2)
        last = value
        return value
    }

    /** Forget the cached answer (e.g. on stop()) so the next start check is evaluated fresh. */
    fun reset() {
        nextTick = 0
    }
}
