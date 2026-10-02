package com.sbwnpc.squad.integration.sbw.client

import com.atsuishio.superbwarfare.item.gun.GunItem
import com.mojang.blaze3d.vertex.PoseStack
import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.domain.port.GunRendering
import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.ClientHooks
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent

/** NPC guns never enter ItemRenderer.render or SBW's GeoItemRenderer, including on load failure. */
@EventBusSubscriber(Dist.CLIENT)
object SbwStaticGunRendering : SimplePreparableReloadListener<Map<ResourceLocation, StaticGunModel>>(), GunRendering {
    private var guns = emptyMap<ResourceLocation, StaticGunModel>()
    private val reported = HashSet<ResourceLocation>()

    override fun supports(stack: ItemStack): Boolean = stack.item is GunItem

    override fun render(
        holder: LivingEntity,
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        val gun = guns[BuiltInRegistries.ITEM.getKey(stack.item)] ?: return

        // Only apply the existing item model's hand transform; never call its custom renderer.
        val minecraft = Minecraft.getInstance()
        val itemModel = minecraft.itemRenderer.getModel(stack, holder.level(), holder, holder.id)
        ClientHooks.handleCameraTransforms(poseStack, itemModel, displayContext, displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND)
        // ItemRenderer's (-0.5, -0.5, -0.5) and GeoItemRenderer's (0.5, 0.51, 0.5) cancel.
        poseStack.translate(0.0f, 0.01f, 0.0f)
        gun.mesh.render(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(gun.texture)), packedLight)

        if (reported.add(gun.geometry)) {
            SquadMod.LOGGER.info("NPC gun renderer: {} ({} quads), static rendering without GeckoLib", gun.geometry, gun.mesh.quadCount)
        }
    }

    override fun prepare(resourceManager: ResourceManager, profiler: ProfilerFiller): Map<ResourceLocation, StaticGunModel> =
        StaticGunModels.load(resourceManager)

    override fun apply(prepared: Map<ResourceLocation, StaticGunModel>, resourceManager: ResourceManager, profiler: ProfilerFiller) {
        guns = prepared
        reported.clear()
        val meshes = guns.values.map { it.mesh }.toSet()
        SquadMod.LOGGER.info("NPC gun renderer: loaded {} guns, {} static LOD models ({} quads)", guns.size, meshes.size, meshes.sumOf { it.quadCount })
    }

    @SubscribeEvent
    fun registerReloadListener(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(this)
    }

    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        Ports.gunRendering = this
    }
}
