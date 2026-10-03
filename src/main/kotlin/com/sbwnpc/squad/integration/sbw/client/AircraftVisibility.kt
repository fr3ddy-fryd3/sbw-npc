package com.sbwnpc.squad.integration.sbw.client

import com.sbwnpc.squad.integration.sbw.SbwAircraftSnapshots
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import kotlin.math.tan

/** Aircraft use SBW's existing BVR pass independently of terrain view distance. */
object AircraftVisibility {
    const val RANGE = 512

    @JvmStatic
    fun isAircraft(entity: Entity): Boolean = SbwAircraftSnapshots.isAircraft(entity)

    @JvmStatic
    fun usesBvr(entity: Entity): Boolean {
        val minecraft = Minecraft.getInstance()
        return isAircraft(entity) && entity.level() === minecraft.level &&
            minecraft.player?.let { entity.hasIndirectPassenger(it) } != true
    }

    fun projection(source: Matrix4f, range: Int): Matrix4f {
        val near = source.m32() / (source.m22() - 1f)
        val far = range.toFloat() + 512f
        return Matrix4f(source).apply {
            m22(-(far + near) / (far - near))
            m32(-(2f * far * near) / (far - near))
        }
    }

    internal fun lodScale(projectionY: Float, normalFov: Int): Double {
        if (!projectionY.isFinite() || projectionY <= 0f) return 1.0
        return (1.0 / (projectionY * tan(Math.toRadians(normalFov.toDouble()) / 2))).coerceIn(0.01, 1.0)
    }
}
