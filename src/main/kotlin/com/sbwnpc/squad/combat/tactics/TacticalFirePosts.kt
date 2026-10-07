package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPosition
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Local fire-position work must not turn a waiting or covering member into an assault mover. */
object TacticalFirePosts {
    const val RECOVERY_RADIUS = 24.0

    fun probes(anchor: Vec3, recovery: Boolean = false): List<Vec3> =
        buildSet {
            add(anchor)
            // One-block spaces next to walls are missed by four-block rings.
            val nearby = (-4..4).flatMap { x -> (-4..4).map { z -> Vec3(x.toDouble(), 0.0, z.toDouble()) } }
                .sortedBy { it.lengthSqr() }
            nearby.forEach { add(anchor.add(it)) }
            for (radius in listOf(4.0, 8.0, CombatPosition.LOCAL_MOVE_RADIUS)) {
                for (sector in 0 until 8) {
                    val angle = sector * Math.PI / 4.0
                    add(anchor.add(cos(angle) * radius, 0.0, sin(angle) * radius))
                }
            }
            if (recovery) {
                for (radius in listOf(16.0, 20.0, RECOVERY_RADIUS)) {
                    for (sector in 0 until 16) {
                        val angle = sector * Math.PI / 8.0
                        add(anchor.add(cos(angle) * radius, 0.0, sin(angle) * radius))
                    }
                }
            }
        }.toList()

    fun withinPost(anchor: Vec3, candidate: Vec3, recovery: Boolean = false): Boolean = candidate.subtract(anchor).horizontalDistance() <= (if (recovery) RECOVERY_RADIUS else CombatPosition.LOCAL_MOVE_RADIUS) + 0.01

    /** An exhausted local query already tried the owned strip; repeating it is not movement progress. */
    fun recoverAfterSearch(task: TacticalTask, from: Vec3): Boolean = task.job in TacticalJob.FIRING_POSITIONS && task.frontage?.let { withinPost(it.point, from, recovery = true) } == true

    /** A failed strip search may use nearby terrain without losing the job or its fixed search origin. */
    fun expand(task: TacticalTask): Boolean {
        if (!task.localFireRecovery || task.expandedFireSearch || task.frontage == null) return false
        task.expandedFireSearch = true
        task.position = null
        task.search = null
        return true
    }

    /** An unsuccessful coordinated search still owns the member's job and threat sector. */
    fun recover(task: TacticalTask, post: Vec3): Boolean {
        if (task.job !in TacticalJob.FIRING_POSITIONS) return false
        task.localFirePost = task.frontage?.point ?: post
        task.position = null
        task.search = null
        return true
    }
}
