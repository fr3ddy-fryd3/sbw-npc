package com.sbwnpc.squad.integration.sbw.client

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance
import com.atsuishio.superbwarfare.entity.vehicle.VehicleModelEntry
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.model.VehicleLODModelReloadListener
import com.atsuishio.superbwarfare.resource.model.VehicleModelReloadListener
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.atsuishio.superbwarfare.tools.RenderDistanceHelper
import com.sbwnpc.squad.SquadMod
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.GsonUtil
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import java.util.WeakHashMap
import kotlin.math.ceil

/** Baked meshes are shared; only mutable bone poses belong to individual aircraft. */
object AircraftLods {
    private var resources: ResourceManager? = null
    private val generated = HashMap<ResourceLocation, List<BakedBedrockModel>>()
    private val entries = WeakHashMap<VehicleEntity, List<VehicleModelEntry>>()
    private val builtins = setOf("a_10a", "ac_130h", "ah_6", "j_16", "ju_87", "kv_16", "mi_28", "tom_6")

    @JvmStatic
    fun reload(models: Map<ResourceLocation, BedrockModelPOJO>, resourceManager: ResourceManager) {
        resources = resourceManager
        generated.clear()
        entries.clear()
        for (path in models.keys) {
            if (path.namespace == "superbwarfare" && path.path.substringAfterLast('/').removeSuffix(".geo.json") in builtins) {
                loadVariants(path)?.let { generated[path] = it }
            }
        }
    }

    @JvmStatic
    fun augment(entity: VehicleEntity, original: List<VehicleModelEntry>): List<VehicleModelEntry> {
        if (original.isEmpty() || !AircraftVisibility.isAircraft(entity)) return original
        return entries.getOrPut(entity) {
            val resourceModels = VehicleResource.compute(entity).getModels()
            val mainPath = resourceModels.firstOrNull { it.distance == 0 }?.model ?: return@getOrPut original
            val result = original.toMutableList()
            // Retain SBW's authored AH-6 meshes, with room for all three distance steps.
            if (mainPath.toString() == "superbwarfare:models/bedrock/vehicle/ah_6.geo.json" &&
                original.map { it.lodDistance } == listOf(0, 32, 64, 96)) {
                return@getOrPut original.mapIndexed { index, entry ->
                    entry.copy(lodDistance = intArrayOf(0, 64, 128, 256)[index])
                }
            }
            // Some native configs put an LOD-folder model at distance zero (not loaded by SBW).
            for (model in resourceModels) {
                val path = model.model ?: continue
                if (model.distance != 0 || !path.path.startsWith("models/bedrock/vehicle_lod/")) continue
                val baked = VehicleLODModelReloadListener.getModel(path) ?: continue
                val texture = model.texture ?: continue
                result += VehicleModelEntry(VehicleModelInstance(baked), texture, model.emissiveTexture, 64)
            }
            // Respect resource packs that already provide working distant models.
            if (result.any { it.lodDistance >= 128 }) return@getOrPut result.sortedBy { it.lodDistance }
            val meshes = generated[mainPath] ?: loadVariants(mainPath)?.also { generated[mainPath] = it }
                ?: return@getOrPut result.sortedBy { it.lodDistance }
            for ((index, mesh) in meshes.withIndex()) {
                result += original.first().copy(instance = VehicleModelInstance(mesh), lodDistance = if (index == 0) 128 else 256)
            }
            result.sortedBy { it.lodDistance }
        }
    }

    @JvmStatic
    fun select(entity: VehicleEntity, models: List<VehicleModelEntry>, stack: PoseStack): VehicleModelEntry? {
        val scale = AircraftVisibility.lodScale(RenderSystem.getProjectionMatrix().m11(), Minecraft.getInstance().options.fov().get())
        val matrix = stack.last().pose()
        val distanceSq = (matrix.m30() * matrix.m30() + matrix.m31() * matrix.m31() + matrix.m32() * matrix.m32()) * scale * scale
        return highestLod(models) {
            distanceSq >= it.lodDistance.toDouble() * it.lodDistance &&
                RenderDistanceHelper.shouldRenderLOD(entity, stack, it.lodDistance.toDouble())
        }
    }

    internal inline fun highestLod(models: List<VehicleModelEntry>, allowed: (VehicleModelEntry) -> Boolean): VehicleModelEntry? {
        var selected: VehicleModelEntry? = null
        for (entry in models) {
            if (entry.lodDistance > 0 && entry.lodDistance > (selected?.lodDistance ?: 0) && allowed(entry)) selected = entry
        }
        return selected ?: models.firstOrNull()
    }

    private fun loadVariants(path: ResourceLocation): List<BakedBedrockModel>? {
        val resource = resources?.getResource(path)?.orElse(null) ?: return null
        return try {
            val source = resource.openAsReader().use { JsonParser.parseReader(it).asJsonObject }
            if (!source.has("minecraft:geometry")) return null
            listOf(0.35, 0.15).map { fraction -> bake(simplify(source, fraction)) }
        } catch (exception: Exception) {
            SquadMod.LOGGER.warn("Cannot simplify aircraft model {}; keeping its native models", path, exception)
            null
        }
    }

    internal fun bake(source: JsonObject): BakedBedrockModel = BakedBedrockModel.bake(
        GsonUtil.CLIENT_GSON.fromJson(source, BedrockModelPOJO::class.java),
        BakerOptions.defaults().withPreservedBoneRegexes(VehicleModelReloadListener.PATTERNS)
    )

    /** Keep broad panels (including thin wings/rotors), bone transforms, UVs and attachment names. */
    internal fun simplify(source: JsonObject, fraction: Double): JsonObject {
        val tree = source.deepCopy()
        val bones = tree.getAsJsonArray("minecraft:geometry").first().asJsonObject.getAsJsonArray("bones")
        val cubes = bones.flatMap { it.asJsonObject.getAsJsonArray("cubes")?.toList() ?: emptyList() }
            .map { it.asJsonObject }
        if (cubes.isEmpty()) return tree
        fun area(cube: JsonObject): Double {
            val sides = cube.getAsJsonArray("size").map { kotlin.math.abs(it.asDouble) }.sortedDescending()
            return sides[0] * sides[1]
        }
        val ranked = cubes.sortedByDescending(::area)
        val cutoff = area(ranked[(ceil(ranked.size * fraction).toInt() - 1).coerceIn(0, ranked.lastIndex)])
        for (element in bones) {
            val bone = element.asJsonObject
            val parts = bone.getAsJsonArray("cubes") ?: continue
            val largestArea = parts.maxOfOrNull { area(it.asJsonObject) } ?: continue
            val retained = JsonArray()
            for (cube in parts) {
                val score = area(cube.asJsonObject)
                // Every modeled component keeps its broadest panels, including symmetric ties.
                // This avoids floating wings/hulls when narrow structural supports are small.
                if (score >= cutoff || score >= largestArea) retained.add(cube)
            }
            bone.add("cubes", retained)
        }
        return tree
    }
}
