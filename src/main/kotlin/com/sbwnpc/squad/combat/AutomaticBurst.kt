package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.util.RandomSource

/** Automatic trigger discipline; temporary loss of aim does not start a fresh ammunition budget. */
class AutomaticBurst {
    private var rounds = 0
    private var limit = 0
    private var pauseUntil = 0

    fun paused(tick: Int): Boolean = tick < pauseUntil

    fun fired(role: NpcClass, tick: Int, random: RandomSource) {
        val machineGun = role == NpcClass.MACHINE_GUNNER
        if (limit == 0) limit = (if (machineGun) 6 else 3) + random.nextInt(3)
        if (++rounds >= limit) {
            rounds = 0
            limit = 0
            pauseUntil = tick + if (machineGun) 12 + random.nextInt(11) else 20 + random.nextInt(16)
        }
    }

    fun reset() {
        rounds = 0
        limit = 0
        pauseUntil = 0
    }
}
