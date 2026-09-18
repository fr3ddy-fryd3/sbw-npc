package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import kotlin.math.cos

internal object DroneAim {
    private val minimumAlignment = cos(Math.toRadians(3.0))

    /** Check allies along the actual barrel direction, only after the NPC has turned to its target. */
    fun firingEndpoint(origin: Vec3, look: Vec3, target: Vec3): Vec3? {
        val offset = target.subtract(origin)
        val distance = offset.length()
        if (distance < 1.0e-6 || look.lengthSqr() < 1.0e-12) return null
        val direction = look.normalize()
        if (direction.dot(offset.scale(1.0 / distance)) < minimumAlignment) return null
        return origin.add(direction.scale(distance))
    }
}
