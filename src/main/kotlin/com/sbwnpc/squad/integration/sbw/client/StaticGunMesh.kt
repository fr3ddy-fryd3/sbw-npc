package com.sbwnpc.squad.integration.sbw.client

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.GsonUtil
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedQuadData
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.texture.OverlayTexture
import org.joml.Vector3f
import java.io.Reader

/** Bone and cube transforms are folded into vertex arrays once, at resource loading time. */
class StaticGunMesh private constructor(private val chunks: Array<BakedQuadData>) {
    val quadCount: Int = chunks.sumOf { it.quadCount() }

    fun render(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int) {
        val pose = poseStack.last()
        val position = Vector3f()
        val normal = Vector3f()
        for (chunk in chunks) {
            val positions = chunk.positions()
            val normals = chunk.normals()
            val uvs = chunk.uvs()
            for (quad in 0 until chunk.quadCount()) {
                val n = quad * BakedQuadData.NORMAL_STRIDE
                pose.transformNormal(normals[n], normals[n + 1], normals[n + 2], normal)
                for (vertex in 0..3) {
                    val p = quad * BakedQuadData.POSITION_STRIDE + vertex * 3
                    val uv = quad * BakedQuadData.UV_STRIDE + vertex * 2
                    pose.pose().transformPosition(positions[p], positions[p + 1], positions[p + 2], position)
                    buffer.addVertex(
                        position.x, position.y, position.z, -1, uvs[uv], uvs[uv + 1],
                        OverlayTexture.NO_OVERLAY, packedLight, normal.x, normal.y, normal.z
                    )
                }
            }
        }
    }

    companion object {
        fun bake(reader: Reader): StaticGunMesh {
            val source = GsonUtil.CLIENT_GSON.fromJson(reader, BedrockModelPOJO::class.java)
            val chunks = BakedBedrockModel.bake(source, BakerOptions.defaults()).chunks()
            require(chunks.isNotEmpty() && chunks.all { it.isRootAttached && !it.hasVertices() }) {
                "A static gun must bake entirely into root-attached quads"
            }
            return StaticGunMesh(chunks.map { it.quads() }.toTypedArray())
        }
    }
}
