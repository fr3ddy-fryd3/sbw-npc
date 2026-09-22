package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3

/**
 * The arc an NPC can actually spot something in unaided.
 *
 * Until this existed every NPC noticed everything within detection range in every direction,
 * including directly behind it, which made flanking pointless and sneaking impossible.
 *
 * Horizontal only, deliberately: a soldier looking down a street still notices the man on the
 * rooftop above him, and gating on pitch as well would have NPCs walk past enemies standing on a
 * hill. Yaw is the axis that carries the meaning.
 *
 * This gates **first-hand sight only**. Being shot at, a contact relayed by the rest of the
 * faction, and an order to focus on something all bypass it — an NPC that could not be shot in the
 * back, told about an enemy, or ordered onto one would be worse than the one that saw everything.
 */
object Vision {
    /** Total arc, centred on where the NPC is looking. */
    const val CONE_DEGREES = 150.0

    fun inCone(
        viewerPos: Vec3,
        viewYawDegrees: Float,
        targetPos: Vec3,
        coneDegrees: Double = CONE_DEGREES
    ): Boolean {
        val dx = targetPos.x - viewerPos.x
        val dz = targetPos.z - viewerPos.z
        val distSqr = dx * dx + dz * dz
        // Standing on top of each other has no meaningful bearing; anything that close is noticed.
        if (distSqr < 1.0e-6) return true

        // Minecraft yaw: 0 faces +Z, and increases clockwise seen from above.
        val yaw = Math.toRadians(viewYawDegrees.toDouble())
        val forwardX = -Math.sin(yaw)
        val forwardZ = Math.cos(yaw)

        val cos = (forwardX * dx + forwardZ * dz) / Math.sqrt(distSqr)
        return cos >= Math.cos(Math.toRadians(coneDegrees / 2.0))
    }
}
