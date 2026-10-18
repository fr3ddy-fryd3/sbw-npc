package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** A firing post is a block-sized place; formation arrival tolerance cannot confirm its cover. */
object FiringPostArrival {
    private const val HORIZONTAL_TOLERANCE = 0.4
    private const val HEIGHT_TOLERANCE = 0.6
    private const val FINAL_STEP_RADIUS = 2.5

    fun reached(position: Vec3, post: Vec3): Boolean = position.subtract(post).horizontalDistance() <= HORIZONTAL_TOLERANCE && kotlin.math.abs(position.y - post.y) <= HEIGHT_TOLERANCE

    /** Finish the last unobstructed steps after vanilla advances its final waypoint. */
    fun steer(entity: NpcEntity, post: Vec3, speed: Double): Boolean {
        if (entity.navigation.isDone.not() || reached(entity.position(), post)) return false
        val delta = post.subtract(entity.position())
        if (delta.horizontalDistance() > FINAL_STEP_RADIUS || kotlin.math.abs(delta.y) > HEIGHT_TOLERANCE) return false
        val level = entity.level() as ServerLevel
        if (!level.noCollision(entity, entity.boundingBox.expandTowards(delta))) return false
        val steps = kotlin.math.ceil(delta.horizontalDistance() / 0.25).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            val point = entity.position().add(delta.scale(step.toDouble() / steps))
            val feet = Terrain.feetAt(level, point, 2) ?: return false
            if (kotlin.math.abs(feet.y - point.y) > HEIGHT_TOLERANCE) return false
        }
        entity.moveControl.setWantedPosition(post.x, post.y, post.z, speed)
        return true
    }
}
