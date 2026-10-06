package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Weapon direction is independent of the body's walking rotation. */
object ShotAim {
    fun direction(from: Vec3, to: Vec3, arcPitch: Float? = null): Vec3 {
        val delta = to.subtract(from)
        if (arcPitch == null) return delta.normalize()
        val flat = Vec3(delta.x, 0.0, delta.z).normalize()
        // Minecraft pitch is negative when looking up.
        val elevation = Math.toRadians(-arcPitch.toDouble())
        return flat.scale(cos(elevation)).add(0.0, sin(elevation), 0.0)
    }
}
