package com.sbwnpc.squad.client

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance
import com.atsuishio.superbwarfare.entity.vehicle.VehicleModelEntry
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import com.sbwnpc.squad.integration.sbw.client.AircraftLods
import com.sbwnpc.squad.integration.sbw.client.AircraftVisibility
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AircraftLodsTest {
    @Test
    fun `all built in aircraft bake cheaper distant geometry without changing their skeleton`() {
        val root = File(System.getProperty("sbwnpc.projectDir"), "SuperbWarfare/src/main/resources/assets/superbwarfare/models/bedrock/vehicle")
        for (name in listOf("a_10a", "ac_130h", "ah_6", "j_16", "ju_87", "kv_16", "mi_28", "tom_6")) {
            val source = File(root, "$name.geo.json").reader().use {
                JsonParser.parseReader(it).asJsonObject
            }
            val originalTree = source.deepCopy()
            val originalBones = originalTree.getAsJsonArray("minecraft:geometry").first().asJsonObject.getAsJsonArray("bones")
            fun quads(model: BakedBedrockModel) = model.chunks().sumOf { it.quads().quadCount() }
            fun bake(json: com.google.gson.JsonObject) = AircraftLods.bake(json)
            val full = bake(source)
            var previousQuads = quads(full)
            for (fraction in listOf(0.35, 0.15)) {
                val reduced = AircraftLods.simplify(source, fraction)
                val baked = bake(reduced)
                val count = quads(baked)
                assertTrue(count in 1 until previousQuads, "$name/$fraction must reduce quads: $count >= $previousQuads")
                assertTrue(baked.chunks().all { chunk -> chunk.quads().positions().all { it.isFinite() } }, name)
                for (bone in listOf("root", "move_propeller", "move_tailPropeller")) {
                    if (full.getIndex(bone) >= 0) assertTrue(baked.getIndex(bone) >= 0, "$name lost runtime bone $bone")
                }
                val tree = reduced
                val bones = tree.getAsJsonArray("minecraft:geometry").first().asJsonObject.getAsJsonArray("bones")
                assertEquals(originalBones.size(), bones.size(), "$name keeps rotor, wing and attachment bones")
                for (i in 0 until bones.size()) {
                    if ((originalBones[i].asJsonObject.getAsJsonArray("cubes")?.size() ?: 0) > 0) {
                        assertTrue(bones[i].asJsonObject.getAsJsonArray("cubes").size() > 0, "$name lost modeled component $i")
                    }
                    val before = originalBones[i].asJsonObject.deepCopy().apply { remove("cubes") }
                    val after = bones[i].asJsonObject.deepCopy().apply { remove("cubes") }
                    assertEquals(before, after, "$name bone $i keeps its bind transform")
                }
                previousQuads = count
            }
            assertEquals(originalTree, source, "$name source model was mutated")
        }
    }

    @Test
    fun `thin broad wings survive simplification ahead of bulky small details`() {
        val json = """{"format_version":"1.12.0","minecraft:geometry":[{
          "description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
          "bones":[{"name":"root","pivot":[0,0,0],"cubes":[
            {"origin":[0,0,0],"size":[160,0.1,16],"uv":[0,0]},
            {"origin":[0,0,0],"size":[4,4,4],"uv":[0,0]},
            {"origin":[0,0,0],"size":[3,3,3],"uv":[0,0]}
          ]}]}]}"""
        val source = JsonParser.parseString(json).asJsonObject
        val reduced = AircraftLods.simplify(source, 0.15)
        val cubes = reduced.getAsJsonArray("minecraft:geometry").first().asJsonObject
            .getAsJsonArray("bones").first().asJsonObject.getAsJsonArray("cubes")
        assertEquals(1, cubes.size())
        assertEquals(160.0, cubes.first().asJsonObject.getAsJsonArray("size")[0].asDouble)
    }

    @Test
    fun `extended projection retains near plane and field of view at low terrain distances`() {
        val original = Matrix4f().perspective(Math.toRadians(70.0).toFloat(), 16f / 9f, 0.05f, 128f)
        val result = AircraftVisibility.projection(original, AircraftVisibility.RANGE)
        assertEquals(original.m00(), result.m00())
        assertEquals(original.m11(), result.m11())
        assertEquals(0.05f, result.m32() / (result.m22() - 1f), 0.00001f)
        val far = result.m32() / (result.m22() + 1f)
        assertTrue(far >= AircraftVisibility.RANGE, "Aircraft must remain inside the clip volume")
        assertEquals(128f, original.m32() / (original.m22() + 1f), 0.05f)
    }

    @Test
    fun `LOD selection advances through every distance step and respects disabled LOD`() {
        val source = File(System.getProperty("sbwnpc.projectDir"), "SuperbWarfare/src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ah_6.geo.json")
            .reader().use { JsonParser.parseReader(it).asJsonObject }
        val full = VehicleModelEntry(VehicleModelInstance(AircraftLods.bake(source)), ResourceLocation.parse("test:texture"), null, 0)
        val entries = listOf(full, full.copy(lodDistance = 64), full.copy(lodDistance = 128), full.copy(lodDistance = 256))
        for ((distance, expected) in listOf(63 to 0, 64 to 64, 127 to 64, 128 to 128, 255 to 128, 256 to 256, 512 to 256)) {
            assertEquals(expected, AircraftLods.highestLod(entries) { distance >= it.lodDistance }?.lodDistance)
        }
        assertEquals(full, AircraftLods.highestLod(entries) { false })
        val narrow = Matrix4f().perspective(Math.toRadians(7.0).toFloat(), 16f / 9f, 0.05f, 1024f)
        val scale = AircraftVisibility.lodScale(narrow.m11(), 70)
        assertEquals(full, AircraftLods.highestLod(entries) { 512 * scale >= it.lodDistance }, "Scoped aircraft retain detail")
    }
}
