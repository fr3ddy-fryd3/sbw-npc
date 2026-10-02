package com.sbwnpc.squad.client

import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.sbwnpc.squad.integration.sbw.client.StaticGunMesh
import com.sbwnpc.squad.integration.sbw.client.StaticGunModels
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class StaticGunMeshTest {
    private val assets = File(System.getProperty("sbwnpc.projectDir"), "SuperbWarfare/src/main/resources/assets")

    @Test
    fun `every issued weapon has a renderable static mesh and its matching texture`() {
        val guns = gunResources(assets.parentFile).use(StaticGunModels::load)
        val weapons = NpcClass.entries.flatMap { it.weaponPool }.map { it.path }.toSet() + "rpg"
        for (weapon in weapons) {
            val resource = File(assets, "superbwarfare/sbw/guns/$weapon.json").reader().use {
                JsonParser.parseReader(it).asJsonObject.getAsJsonObject("Model")
            }
            val model = asset(resource.get("LODModel").asString)
            val texture = asset(resource.get("LODTexture").asString)
            assertTrue(model.isFile, "Missing $weapon model: $model")
            assertTrue(texture.isFile, "Missing $weapon texture: $texture")
            val gun = requireNotNull(guns[ResourceLocation.fromNamespaceAndPath("superbwarfare", weapon)]) { "Missing NPC model for $weapon" }
            assertEquals(resource.get("LODModel").asString, gun.geometry.toString(), weapon)
            assertEquals(resource.get("LODTexture").asString, gun.texture.toString(), weapon)
            val mesh = gun.mesh
            val vertices = RecordingVertices()
            mesh.render(PoseStack(), vertices, LIGHT)
            assertEquals(mesh.quadCount * 4, vertices.positions.size, weapon)
            assertTrue(mesh.quadCount in 1..300, "$weapon must use the simplified mesh")
            assertTrue(vertices.positions.all { it.isFinite }, "$weapon has invalid positions")
            assertTrue(vertices.normals.all { it.isFinite && kotlin.math.abs(it.length() - 1) < 0.001f }, "$weapon has invalid normals")
            assertTrue(vertices.uvs.all { it.isFinite() }, "$weapon has invalid UV coordinates")
            assertTrue(vertices.lights.all { it == LIGHT }, "$weapon lost its world lighting")
        }
    }

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
            """.reader()
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
            """.reader()
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

    private fun asset(location: String): File = File(assets, location.replace(':', '/'))

    private class RecordingVertices : VertexConsumer {
        val positions = ArrayList<Vector3f>()
        val normals = ArrayList<Vector3f>()
        val uvs = ArrayList<Float>()
        val lights = ArrayList<Int>()

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = apply { positions += Vector3f(x, y, z) }
        override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = apply { normals += Vector3f(x, y, z) }
        override fun setUv(u: Float, v: Float): VertexConsumer = apply { uvs += u; uvs += v }
        override fun setUv2(u: Int, v: Int): VertexConsumer = apply { lights += u or (v shl 16) }
        override fun setUv1(u: Int, v: Int): VertexConsumer = this
        override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = this
    }

    private companion object {
        const val LIGHT = 0x00A000B0
    }
}
