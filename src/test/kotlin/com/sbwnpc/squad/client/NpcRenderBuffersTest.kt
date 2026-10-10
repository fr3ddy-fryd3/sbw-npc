package com.sbwnpc.squad.client

import com.atsuishio.superbwarfare.item.armor.RuChest6b43Item
import com.atsuishio.superbwarfare.item.armor.RuHelmet6b47Item
import com.atsuishio.superbwarfare.item.armor.UsChestIotvItem
import com.atsuishio.superbwarfare.item.armor.UsHelmetPasgtItem
import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.sbwnpc.squad.integration.sbw.client.SbwNpcRenderBuffers
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.SequencedMap

class NpcRenderBuffersTest {
    @Test
    fun `opaque skins and issued armor consolidate across NPCs with native buffers`() {
        val types = SbwNpcRenderBuffers.renderTypes()
        assertEquals(12, types.toSet().size)
        assertTrue(types.all { it.canConsolidateConsecutiveGeometry() && !it.sortOnUpload() })
        val baseline = renderCrowd(192, batched = false)
        val batched = renderCrowd(192, batched = true)
        assertEquals(baseline.vertices, batched.vertices, "Batching must preserve all emitted geometry")
        assertEquals(576, baseline.uploads, "One skin, helmet and vest upload per NPC without fixed buffers")
        assertEquals(12, batched.uploads, "Each of eight skins and four armor textures uploads once")
        println("NPC_RENDER_BATCH crowd=192 before=${baseline.uploads} after=${batched.uploads} vertices=${batched.vertices}")
    }

    @Test
    fun `entity pass flushes NPC depth while retaining unrelated fixed buffers`() {
        val fixed = LinkedHashMap<RenderType, ByteBufferBuilder>()
        SbwNpcRenderBuffers.register(RegisterRenderBuffersEvent(fixed))
        val unrelated = RenderType.entityCutoutNoCull(ResourceLocation.fromNamespaceAndPath("test", "other.png"))
        fixed[unrelated] = ByteBufferBuilder(1536)
        val shared = ByteBufferBuilder(1536)
        try {
            val buffers = UploadCounter(shared, fixed)
            SbwNpcRenderBuffers.renderTypes().forEach { quad(buffers.getBuffer(it)) }
            quad(buffers.getBuffer(unrelated))
            SbwNpcRenderBuffers.flush(buffers)
            assertEquals(12, buffers.uploads)
            assertEquals(48, buffers.vertices)
            buffers.endBatch()
            assertEquals(13, buffers.uploads)
            assertEquals(52, buffers.vertices)
        } finally {
            fixed.values.forEach(ByteBufferBuilder::close)
            shared.close()
        }
    }

    private data class Result(val uploads: Int, val vertices: Int)

    private fun renderCrowd(count: Int, batched: Boolean): Result {
        val fixed = LinkedHashMap<RenderType, ByteBufferBuilder>()
        if (batched) SbwNpcRenderBuffers.register(RegisterRenderBuffersEvent(fixed))
        val shared = ByteBufferBuilder(1536)
        try {
            val buffers = UploadCounter(shared, fixed)
            val types = SbwNpcRenderBuffers.renderTypes()
            val armorTextures = listOf(RuHelmet6b47Item.TEXTURE, RuChest6b43Item.TEXTURE, UsHelmetPasgtItem.TEXTURE, UsChestIotvItem.TEXTURE)
            repeat(count) { i ->
                quad(buffers.getBuffer(types[i % SquadFaction.entries.size]))
                val uniform = if (SquadFaction.entries[i % SquadFaction.entries.size].greenUniform) 8 else 10
                for (slot in uniform..uniform + 1) {
                    quad(buffers.getBuffer(types[slot]))
                    // SBW also requests a poly-mesh buffer even when the armor contains only cubes.
                    buffers.getBuffer(BedrockModelRenderTypes.polyMeshCutout(armorTextures[slot - 8]))
                }
            }
            if (batched) SbwNpcRenderBuffers.flush(buffers)
            buffers.endBatch()
            return Result(buffers.uploads, buffers.vertices)
        } finally {
            fixed.values.forEach(ByteBufferBuilder::close)
            shared.close()
        }
    }

    private fun quad(buffer: com.mojang.blaze3d.vertex.VertexConsumer) {
        repeat(4) { buffer.addVertex(it.toFloat(), 0f, 0f, -1, 0f, 0f, 0, 0x00F000F0, 0f, 1f, 0f) }
    }

    /** Native getBuffer and endBatch iteration; only GPU submission is replaced for this test. */
    private class UploadCounter(shared: ByteBufferBuilder, fixed: SequencedMap<RenderType, ByteBufferBuilder>) : MultiBufferSource.BufferSource(shared, fixed) {
        var uploads = 0
        var vertices = 0

        override fun endBatch(type: RenderType) {
            val builder: BufferBuilder = startedBuilders.remove(type) ?: return
            builder.build()?.use {
                uploads++
                vertices += it.drawState().vertexCount()
            }
            if (lastSharedType == type) lastSharedType = null
        }
    }
}
