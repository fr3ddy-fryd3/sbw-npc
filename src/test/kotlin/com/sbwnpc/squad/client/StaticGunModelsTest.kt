package com.sbwnpc.squad.client

import com.atsuishio.superbwarfare.data.DataLoader
import com.atsuishio.superbwarfare.resource.ModelResource
import com.google.gson.JsonParser
import com.sbwnpc.squad.integration.sbw.client.StaticGunModels
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Optional

internal fun gunResources(vararg roots: File): MultiPackResourceManager = MultiPackResourceManager(
    PackType.CLIENT_RESOURCES,
    roots.mapIndexed { index, root ->
        PathPackResources(
            PackLocationInfo("test-$index", Component.literal("test"), PackSource.BUILT_IN, Optional.empty()),
            root.toPath()
        )
    }
)

class StaticGunModelsTest {
    private val resources = File(System.getProperty("sbwnpc.projectDir"), "SuperbWarfare/src/main/resources")

    @TempDir
    lateinit var overrides: Path

    @Test
    fun `NPC selection retains exact LOD fields despite SBW Gson field naming`() {
        val json = File(resources, "assets/superbwarfare/sbw/guns/ak_47.json").reader().use {
            JsonParser.parseReader(it).asJsonObject.getAsJsonObject("Model")
        }
        // Exercise SBW's actual decoder as well as the NPC resource loader. The full model
        // remains usable in SBW even when its Kotlin LOD fields were ignored by Gson.
        val decoded = DataLoader.GSON.fromJson(json, ModelResource::class.java)
        val gun = gunResources(resources).use(StaticGunModels::load).getValue(id("superbwarfare:ak_47"))
        assertEquals(json.get("LODModel").asString, gun.geometry.toString())
        assertEquals(json.get("LODTexture").asString, gun.texture.toString())
        assertNotEquals(decoded.model, gun.geometry)
        assertTrue(gun.mesh.quadCount < 300)
    }

    @Test
    fun `resource pack LOD arrays and geometry outside geo lod are used on reload`() {
        overrideResource("superbwarfare:sbw/guns/ak_47.json", """
            {"Model": {
              "Model": "superbwarfare:geo/ak_47.geo.json",
              "Texture": "superbwarfare:textures/item/ak_47.png",
              "LODModel": ["superbwarfare:geo/lod/ak_47.geo.json", "test:models/npc.json"],
              "LODTexture": ["superbwarfare:textures/item/lod/ak_47.png", "superbwarfare:textures/item/lod/ak_12.png"]
            }}
        """)
        val simplified = File(resources, "assets/superbwarfare/geo/lod/ak_12.geo.json").readText()
        overrideResource("test:models/npc.json", simplified)
        val original = gunResources(resources).use(StaticGunModels::load).getValue(id("superbwarfare:ak_47"))
        val replacement = gunResources(resources, overrides.toFile()).use(StaticGunModels::load).getValue(id("superbwarfare:ak_47"))
        assertEquals(id("test:models/npc.json"), replacement.geometry)
        assertEquals(id("superbwarfare:textures/item/lod/ak_12.png"), replacement.texture)
        assertNotEquals(original.geometry, replacement.geometry)
        assertTrue(replacement.mesh.quadCount < 300)
    }

    @Test
    fun `missing or broken LOD never falls back to full geometry`() {
        overrideResource("superbwarfare:sbw/guns/ak_47.json", """
            {"Model": {"Model": "superbwarfare:geo/ak_47.geo.json", "Texture": "superbwarfare:textures/item/ak_47.png"}}
        """)
        overrideResource("superbwarfare:sbw/guns/ak_12.json", """
            {"Model": {
              "Model": "superbwarfare:geo/ak_12.geo.json", "Texture": "superbwarfare:textures/item/ak_12.png",
              "LODModel": "test:models/missing.json"
            }}
        """)
        val guns = gunResources(resources, overrides.toFile()).use(StaticGunModels::load)
        assertFalse(guns.containsKey(id("superbwarfare:ak_47")))
        assertFalse(guns.containsKey(id("superbwarfare:ak_12")))
        assertTrue(guns.containsKey(id("superbwarfare:mp_5")))
    }

    @Test
    fun `a LOD may share the full model texture without sharing its geometry`() {
        overrideResource("superbwarfare:sbw/guns/ak_47.json", """
            {"Model": {
              "Model": "superbwarfare:geo/ak_47.geo.json", "Texture": "superbwarfare:textures/item/ak_47.png",
              "LODModel": "superbwarfare:geo/lod/ak_47.geo.json", "LODTexture": []
            }}
        """)
        val gun = gunResources(resources, overrides.toFile()).use(StaticGunModels::load).getValue(id("superbwarfare:ak_47"))
        assertEquals(id("superbwarfare:geo/lod/ak_47.geo.json"), gun.geometry)
        assertEquals(id("superbwarfare:textures/item/ak_47.png"), gun.texture)
    }

    private fun overrideResource(location: String, text: String) {
        val file = overrides.resolve("assets/${location.replace(':', '/')}").toFile()
        file.parentFile.mkdirs()
        file.writeText(text.trimIndent())
    }

    private fun id(location: String): ResourceLocation = ResourceLocation.parse(location)
}
