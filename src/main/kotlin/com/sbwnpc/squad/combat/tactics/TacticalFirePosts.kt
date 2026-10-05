package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPosition
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Local fire-position work must not turn a waiting or covering member into an assault mover. */
object TacticalFirePosts {
    fun probes(anchor: Vec3): List<Vec3> =
        buildList {
            add(anchor)
            for (radius in listOf(4.0, 8.0, CombatPosition.LOCAL_MOVE_RADIUS)) {
                for (sector in 0 until 8) {
                    val angle = sector * Math.PI / 4.0
                    add(anchor.add(cos(angle) * radius, 0.0, sin(angle) * radius))
                }
            }
        }

    fun withinPost(anchor: Vec3, candidate: Vec3): Boolean = candidate.subtract(anchor).horizontalDistance() <= CombatPosition.LOCAL_MOVE_RADIUS + 0.01

    /** An unsuccessful coordinated search still owns the member's job and threat sector. */
    fun recover(task: TacticalTask, post: Vec3): Boolean {
        if (task.job !in TacticalJob.FIRING_POSITIONS) return false
        task.localFirePost = post
        task.position = null
        task.search = null
        return true
    }
}
