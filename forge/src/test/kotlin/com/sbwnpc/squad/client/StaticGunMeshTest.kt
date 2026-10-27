package com.sbwnpc.squad.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.sbwnpc.squad.integration.sbw.client.StaticGunMesh
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The 1.20.1 twin of the NeoForge `StaticGunMeshTest`, for the transforms that need no SBW assets.
 * The test that checks every issued weapon against SBW's resources has no Forge counterpart yet.
 */
class StaticGunMeshTest {
    @Test
    fun `nested bone rotations are baked before applying the hand transform`() {
        val mesh = StaticGunMesh.bake(
            """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [{
                "description": {"identifier": "geometry.test", "texture_width": 64, "texture_height": 64},
                "bones": [
                  {"name": "root", "pivot": [0, 0, 0], "rotation": [0, 0, 90]},
                  {"name": "child", "parent": "root", "pivot": [16, 0, 0], "rotation": [0, 0, 90],
                   "cubes": [{"origin": [16, 0, 0], "size": [16, 16, 16], "uv": [0, 0]}]}
                ]
              }]
            }
            """.reader(),
        )
        val vertices = RecordingVertices()
        val pose = PoseStack().apply { translate(4.0f, 5.0f, 6.0f) }
        mesh.render(pose, vertices, LIGHT)
        assertEquals(24, vertices.positions.size)
        assertEquals(4.0f, vertices.positions.minOf { it.x }, 0.0001f)
        assertEquals(5.0f, vertices.positions.maxOf { it.x }, 0.0001f)
        assertEquals(3.0f, vertices.positions.minOf { it.y }, 0.0001f)
        assertEquals(4.0f, vertices.positions.maxOf { it.y }, 0.0001f)
        assertEquals(6.0f, vertices.positions.minOf { it.z }, 0.0001f)
        assertEquals(7.0f, vertices.positions.maxOf { it.z }, 0.0001f)
    }

    @Test
    fun `cube rotation uses its own pivot and rendering does not accumulate transforms`() {
        val mesh = StaticGunMesh.bake(
            """
            {"format_version": "1.12.0", "minecraft:geometry": [{
              "description": {"identifier": "geometry.test", "texture_width": 64, "texture_height": 64},
              "bones": [{"name": "root", "pivot": [0, 0, 0], "cubes": [{
                "origin": [16, 0, 0], "size": [16, 16, 16], "uv": [0, 0],
                "pivot": [16, 0, 0], "rotation": [0, 0, 90]
              }]}]
            }]}
            """.reader(),
        )
        val first = RecordingVertices()
        val second = RecordingVertices()
        mesh.render(PoseStack(), first, LIGHT)
        mesh.render(PoseStack(), second, LIGHT)
        assertEquals(first.positions, second.positions)
        assertEquals(-2.0f, first.positions.minOf { it.x }, 0.0001f)
        assertEquals(-1.0f, first.positions.maxOf { it.x }, 0.0001f)
        assertEquals(-1.0f, first.positions.minOf { it.y }, 0.0001f)
        assertEquals(0.0f, first.positions.maxOf { it.y }, 0.0001f)
    }

    private class RecordingVertices : VertexConsumer {
        val positions = ArrayList<Vector3f>()

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = apply { positions += Vector3f(x.toFloat(), y.toFloat(), z.toFloat()) }
        override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = this
        override fun uv(u: Float, v: Float): VertexConsumer = this
        override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
        override fun uv2(u: Int, v: Int): VertexConsumer = this
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = this
        override fun endVertex() = Unit
        override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) = Unit
        override fun unsetDefaultColor() = Unit
    }

    private companion object {
        const val LIGHT = 0x00A000B0
    }
}
