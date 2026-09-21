package com.sbwnpc.squad.util

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderLivingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * Answers one question and no other: when the frame rate drops with a lot of NPCs about, is the
 * cost on the server thread or on the render thread?
 *
 * It exists because "low FPS but nothing is using any resources" is the signature of a single
 * saturated thread, and which thread it is decides what there is to fix — a squad's worth of AI
 * on the tick loop and a squad's worth of humanoid models with armour and a held gun in the view
 * frustum are different problems with different answers. Guessing between them is how a week gets
 * spent optimising the wrong one.
 *
 * Both halves are gated on [DebugFlags.LOGGING_ENABLED], so a production jar carries nothing but
 * one already-false branch per tick and per rendered NPC.
 */
@EventBusSubscriber
object PerfProbe {

    private const val REPORT_INTERVAL_MS = 10_000L

    private var tickStart = 0L
    private var tickNanos = 0L
    private var ticks = 0
    private var nextServerReport = 0L

    @SubscribeEvent
    fun onServerTickPre(event: ServerTickEvent.Pre) {
        if (!DebugFlags.LOGGING_ENABLED) return
        tickStart = System.nanoTime()
    }

    @SubscribeEvent
    fun onServerTickPost(event: ServerTickEvent.Post) {
        if (!DebugFlags.LOGGING_ENABLED || tickStart == 0L) return
        tickNanos += System.nanoTime() - tickStart
        ticks++

        val now = System.currentTimeMillis()
        if (nextServerReport == 0L) nextServerReport = now + REPORT_INTERVAL_MS
        if (now < nextServerReport || ticks == 0) return
        nextServerReport = now + REPORT_INTERVAL_MS

        val npcs = event.server.allLevels.sumOf { NpcRegistry.all(it).size }
        // Measured here rather than read off the server's own tick-time array so the number means
        // the same thing on an integrated server as on a dedicated one.
        DebugFlags.log(
            "[perf] server: {} ms/tick over {} ticks, {} NPCs loaded",
            "%.2f".format(tickNanos / 1_000_000.0 / ticks), ticks, npcs
        )
        tickNanos = 0
        ticks = 0
    }

    /**
     * Client half: how many of this mod's NPCs the render thread actually drew, and what the
     * frames cost while it did. A frame time that tracks the NPC count is the answer; one that
     * doesn't means the cost is somewhere else entirely and the NPCs are a red herring.
     */
    @EventBusSubscriber(Dist.CLIENT)
    object Client {
        private var rendered = 0
        private var frames = 0
        private var lastReport = 0L

        @SubscribeEvent
        fun onRenderNpc(event: RenderLivingEvent.Pre<*, *>) {
            if (!DebugFlags.LOGGING_ENABLED) return
            if (event.entity is NpcEntity) rendered++
        }

        @SubscribeEvent
        fun onRenderGui(event: net.neoforged.neoforge.client.event.RenderGuiEvent.Post) {
            if (!DebugFlags.LOGGING_ENABLED) return
            frames++
            val now = System.currentTimeMillis()
            if (lastReport == 0L) {
                lastReport = now
                return
            }
            val elapsed = now - lastReport
            if (elapsed < REPORT_INTERVAL_MS) return
            DebugFlags.log(
                "[perf] client: {} fps, {} NPC renders per frame",
                "%.1f".format(frames * 1000.0 / elapsed),
                "%.1f".format(rendered.toDouble() / frames.coerceAtLeast(1))
            )
            lastReport = now
            frames = 0
            rendered = 0
        }
    }
}
