package com.sbwnpc.squad.integration.sbw.client

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sbwnpc.squad.SquadMod
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager

data class StaticGunModel(
    val geometry: ResourceLocation,
    val texture: ResourceLocation,
    val mesh: StaticGunMesh
)

internal object StaticGunModels {
    fun load(resources: ResourceManager): Map<ResourceLocation, StaticGunModel> {
        val guns = HashMap<ResourceLocation, StaticGunModel>()
        val meshes = HashMap<ResourceLocation, StaticGunMesh>()
        val converter = FileToIdConverter.json("sbw/guns")
        for ((location, resource) in converter.listMatchingResources(resources)) {
            val gun = converter.fileToId(location)
            try {
                // SBW loads ModelResource through Gson, which ignores Kotlin's @SerialName.
                // Its UPPER_CAMEL_CASE policy expects LodModel/LodTexture, so the actual
                // LODModel/LODTexture fields disappear. Read the resource's exact keys here.
                val model = resource.openAsReader().use { JsonParser.parseReader(it).asJsonObject }
                    .getAsJsonObject("Model") ?: continue
                val geometry = lastLocation(model.get("LODModel"))
                if (geometry == null) {
                    SquadMod.LOGGER.warn("NPC gun {} has no LOD model; skipping its static rendering", gun)
                    continue
                }
                val texture = lastLocation(model.get("LODTexture")) ?: lastLocation(model.get("Texture"))
                    ?: error("Missing texture for $gun")
                resources.getResourceOrThrow(texture)
                val mesh = meshes.getOrPut(geometry) {
                    resources.openAsReader(geometry).use(StaticGunMesh::bake)
                }
                guns[gun] = StaticGunModel(geometry, texture, mesh)
            } catch (e: Exception) {
                SquadMod.LOGGER.error("Cannot load NPC static gun {} from {}", gun, location, e)
            }
        }
        return guns
    }

    private fun lastLocation(element: JsonElement?): ResourceLocation? {
        if (element == null || element.isJsonNull) return null
        val value = if (element.isJsonArray) element.asJsonArray.lastOrNull() else element
        return value?.takeUnless { it.isJsonNull }?.asString?.let(ResourceLocation::parse)
    }
}
