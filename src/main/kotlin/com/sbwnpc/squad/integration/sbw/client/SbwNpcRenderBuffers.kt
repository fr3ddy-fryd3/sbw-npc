package com.sbwnpc.squad.integration.sbw.client

import com.atsuishio.superbwarfare.item.armor.RuChest6b43Item
import com.atsuishio.superbwarfare.item.armor.RuHelmet6b47Item
import com.atsuishio.superbwarfare.item.armor.UsChestIotvItem
import com.atsuishio.superbwarfare.item.armor.UsHelmetPasgtItem
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/** Keep opaque NPC geometry in vanilla's fixed buffers until the entity pass is flushed.
 * Without these buffers, each skin/helmet/vest texture switch uploads the previous NPC part.
 * Models, poses, textures, lighting and the native armor renderer are unchanged.
 */
@EventBusSubscriber(Dist.CLIENT)
object SbwNpcRenderBuffers {
    private val types: List<RenderType> by lazy {
        SquadFaction.entries.map { RenderType.entityCutoutNoCull(it.texture) } +
            listOf(
                RuHelmet6b47Item.TEXTURE,
                RuChest6b43Item.TEXTURE,
                UsHelmetPasgtItem.TEXTURE,
                UsChestIotvItem.TEXTURE,
            ).map(RenderType::armorCutoutNoCull)
    }

    internal fun renderTypes(): List<RenderType> = types

    @SubscribeEvent
    fun register(event: RegisterRenderBuffersEvent) {
        renderTypes().forEach(event::registerRenderBuffer)
    }

    @SubscribeEvent
    fun finishEntities(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return
        flush(Minecraft.getInstance().renderBuffers().bufferSource())
    }

    /** Fast/Fancy otherwise flush custom fixed buffers after translucent terrain. Opaque NPCs
     * must write their depth before glass/water, just like the native opaque entity buffers.
     */
    internal fun flush(buffers: MultiBufferSource.BufferSource) {
        types.forEach(buffers::endBatch)
    }
}
