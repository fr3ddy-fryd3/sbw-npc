package com.sbwnpc.squad.integration.sbw.client

import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler
import com.atsuishio.superbwarfare.config.server.SyncConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils
import com.mojang.blaze3d.shaders.FogShape
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.material.FogType
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f

/** Extends SBW's BVR render pass; synchronization and the vehicle renderers stay in SBW. */
object SbwAircraftRendering {
    @JvmStatic
    fun render(event: RenderLevelStageEvent) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val camera = event.camera
        val partialTick = event.partialTick.getGameTimeDeltaPartialTick(true)
        val configuredBvr = SyncConfig.ENABLE_RENDER_SYNCED_ENTITIES.get()
        val aircraft = LinkedHashMap<Int, Entity>()
        val otherRemote = ArrayList<Entity>()
        for (entity in ClientSyncedEntityHandler.getSyncedWorldRenderEntities(level)) {
            if (AircraftVisibility.isAircraft(entity)) aircraft[entity.id] = entity
            else if (configuredBvr && level.getEntity(entity.id) == null) otherRemote += entity
        }
        // Prefer the live entity, including its vanilla interpolation and passenger state.
        for (entity in level.entitiesForRendering()) {
            if (AircraftVisibility.isAircraft(entity)) aircraft[entity.id] = entity
        }
        if (aircraft.isEmpty() && otherRemote.isEmpty()) return

        val buffers = minecraft.renderBuffers().bufferSource()
        buffers.endBatch()
        val savedProjection = Matrix4f(RenderSystem.getProjectionMatrix())
        val savedFogStart = RenderSystem.getShaderFogStart()
        val savedFogEnd = RenderSystem.getShaderFogEnd()
        val savedFogShape = RenderSystem.getShaderFogShape()
        val range = if (configuredBvr) maxOf(AircraftVisibility.RANGE, SyncConfig.MAX_RENDER_DISTANCE.get())
            else AircraftVisibility.RANGE
        val projection = AircraftVisibility.projection(savedProjection, range)
        val frustum = Frustum(event.modelViewMatrix, projection).apply {
            prepare(camera.position.x, camera.position.y, camera.position.z)
        }
        val viewer = camera.entity
        val extendFog = camera.fluidInCamera == FogType.NONE &&
            !(viewer is LivingEntity && (viewer.hasEffect(MobEffects.BLINDNESS) || viewer.hasEffect(MobEffects.DARKNESS))) &&
            !level.effects().isFoggyAt(viewer.blockX, viewer.blockZ)

        fun position(entity: Entity): Vec3? {
            if (level.getEntity(entity.id) === entity) return entity.getPosition(partialTick)
            val entry = ClientSyncedEntityHandler.getWorldRenderEntry(level, entity.id) ?: return null
            if (!entry.shouldWorldRender) return null
            val elapsed = ((System.currentTimeMillis() - entry.timeStamp) / 50.0).coerceIn(0.0, 2.0)
            return entity.position().add(entry.velocity.scale(elapsed))
        }

        fun draw(entity: Entity, pos: Vec3) {
            val blockPos = net.minecraft.core.BlockPos.containing(pos)
            // Unloaded terrain has no light data; aircraft still receive normal sky lighting.
            val light = if (level.hasChunkAt(blockPos)) LevelRenderer.getLightColor(level, blockPos)
                else LightTexture.pack(0, 15)
            val relative = pos.subtract(camera.position)
            minecraft.entityRenderDispatcher.render(
                entity, relative.x, relative.y, relative.z, entity.yRot, partialTick,
                event.poseStack, buffers, light
            )
        }

        try {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN)
            if (extendFog) {
                RenderSystem.setShaderFogStart(AircraftVisibility.RANGE * 0.75f)
                RenderSystem.setShaderFogEnd(AircraftVisibility.RANGE.toFloat())
                RenderSystem.setShaderFogShape(FogShape.SPHERE)
            }
            for (entity in aircraft.values) {
                // Vanilla forces the local player's vehicle into its normal pass.
                if (entity.isRemoved || !AircraftVisibility.usesBvr(entity)) continue
                val pos = position(entity) ?: continue
                if (pos.distanceToSqr(camera.position) > AircraftVisibility.RANGE.toDouble() * AircraftVisibility.RANGE) continue
                val box = VehicleMotionUtils.calculateCombinedAABBOptimized(entity as VehicleEntity)
                    .move(pos.subtract(entity.position())).inflate(3.0)
                if (!frustum.isVisible(box)) continue
                draw(entity, pos)
            }
            buffers.endBatch()

            // Keep the original configured BVR behavior for ships, armor and missiles.
            if (otherRemote.isNotEmpty()) {
                val distance = SyncConfig.MAX_RENDER_DISTANCE.get().toDouble()
                RenderSystem.setShaderFogStart(savedFogEnd)
                RenderSystem.setShaderFogEnd(distance.toFloat())
                RenderSystem.setShaderFogShape(FogShape.SPHERE)
                for (entity in otherRemote) {
                    if (entity.y < SyncConfig.MIN_RENDER_HEIGHT.get()) continue
                    val pos = position(entity) ?: continue
                    if (pos.distanceToSqr(camera.position) <= distance * distance) draw(entity, pos)
                }
            }
        } finally {
            buffers.endBatch()
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.DISTANCE_TO_ORIGIN)
            RenderSystem.setShaderFogStart(savedFogStart)
            RenderSystem.setShaderFogEnd(savedFogEnd)
            RenderSystem.setShaderFogShape(savedFogShape)
        }
    }
}
