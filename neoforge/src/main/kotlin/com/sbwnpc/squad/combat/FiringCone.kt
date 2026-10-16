package com.sbwnpc.squad.combat

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.sqrt

/** Conservative bounds for vanilla Projectile.shoot, used by SBW's rifle projectiles. */
internal class FiringCone(private val from: Vec3, aim: Vec3, spread: Double) {
    private val distance = aim.distanceTo(from)
    private val direction = aim.subtract(from).normalize()

    // Each component gets triangle(0, 0.0172275 * spread). The deviation vector is
    // inside a sphere of radius sqrt(3) * amplitude around the unit aim direction.
    private val deviation = sqrt(3.0) * 0.0172275 * spread.coerceAtLeast(0.0)
    private val angle = if (deviation >= 1.0) Math.PI else asin(deviation)
    val bounds = AABB(from, aim).inflate(2.0 + distance * sin(angle.coerceAtMost(Math.PI / 2.0)))

    fun intersects(box: AABB): Boolean {
        if (distance < 1.0e-6) return false
        // Circumscribe the actual body: a fixed 0.6 sphere misses standing heads/legs.
        val halfSize = Vec3(box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ).scale(0.5)
        val radius = halfSize.length()
        val toBody = box.center.subtract(from)
        val bodyDistance = toBody.length()
        if (bodyDistance - radius > distance) return false
        if (bodyDistance <= radius) return true
        val bodyAngle = acos((toBody.dot(direction) / bodyDistance).coerceIn(-1.0, 1.0))
        val bodyMargin = asin((radius / bodyDistance).coerceIn(0.0, 1.0))
        return bodyAngle <= angle + bodyMargin
    }
}
