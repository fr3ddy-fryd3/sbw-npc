package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3

/**
 * Tells when one NPC's contact with the enemy is over (#186): [CONTACT_END_TICKS] after it last had a
 * target. The caller then makes it wary of the last place it saw an enemy, since the fight is
 * quiet but not necessarily finished. One per NPC; a new target restarts the count.
 */
class ContactTracker {
    private var lastTick = 0
    private var lastAt: Vec3? = null

    /** Feed it every tick; returns where the enemy was last seen on the tick the contact ends, else null. */
    fun update(targetAt: Vec3?, now: Int): Vec3? {
        if (targetAt != null) {
            lastTick = now
            lastAt = targetAt
            return null
        }
        val at = lastAt ?: return null
        if (now - lastTick < CONTACT_END_TICKS) return null
        lastAt = null
        return at
    }

    companion object {
        /** 30 s without a target ends a contact. */
        const val CONTACT_END_TICKS = 600
    }
}
