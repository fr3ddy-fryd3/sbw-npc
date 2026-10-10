package com.sbwnpc.squad.combat.tactics

import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/** A built route survives a ray budget deferral; live clearance is reviewed by its caller. */
internal class TacticalRouteValidation(val path: Path, val builtAt: Long, val candidate: Vec3 = Vec3.atBottomCenterOf(path.target)) {
    enum class Outcome { SAFE, UNSAFE, DEFERRED }
    var nextNode = path.nextNodeIndex
        private set
    var deferred = false
        private set

    fun fresh(now: Long): Boolean = now - builtAt <= 20

    fun resume(check: (Int) -> Outcome): Outcome {
        while (nextNode < path.nodeCount) {
            when (check(nextNode)) {
                Outcome.UNSAFE -> return Outcome.UNSAFE

                Outcome.DEFERRED -> {
                    deferred = true
                    return Outcome.DEFERRED
                }

                Outcome.SAFE -> nextNode++
            }
        }
        return Outcome.SAFE
    }
}
