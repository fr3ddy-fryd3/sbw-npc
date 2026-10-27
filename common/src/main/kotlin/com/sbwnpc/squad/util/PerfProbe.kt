package com.sbwnpc.squad.util

import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.squad.SquadOrder

/**
 * Low-frequency server/render measurements and combat participation counters.
 *
 * It exists because "low FPS but nothing is using any resources" is the signature of a single
 * saturated thread, and which thread it is decides what there is to fix — a squad's worth of AI
 * on the tick loop and a squad's worth of humanoid models with armour and a held gun in the view
 * frustum are different problems with different answers. Guessing between them is how a week gets
 * spent optimising the wrong one.
 *
 * Both halves are gated on [DebugFlags.on] for [LogGroup.PERF], so a production jar carries nothing but
 * one already-false branch per tick and per rendered NPC.
 */
object PerfProbe {

    private const val REPORT_INTERVAL_MS = 10_000L

    private var tickStart = 0L
    private var tickNanos = 0L
    private var ticks = 0
    private var nextServerReport = 0L

    fun onServerTickPre() {
        if (!DebugFlags.on(LogGroup.PERF)) return
        tickStart = System.nanoTime()
    }

    fun onServerTickPost(server: net.minecraft.server.MinecraftServer) {
        if (!DebugFlags.on(LogGroup.PERF) || tickStart == 0L) return
        tickNanos += System.nanoTime() - tickStart
        ticks++

        val now = System.currentTimeMillis()
        if (nextServerReport == 0L) nextServerReport = now + REPORT_INTERVAL_MS
        if (now < nextServerReport || ticks == 0) return
        nextServerReport = now + REPORT_INTERVAL_MS

        val npcs = server.allLevels.flatMap { NpcRegistry.all(it) }
        // Measured here rather than read off the server's own tick-time array so the number means
        // the same thing on an integrated server as on a dedicated one.
        DebugFlags.log(
            LogGroup.PERF,
            "server: {} ms/tick over {} ticks, {} NPCs loaded",
            "%.2f".format(tickNanos / 1_000_000.0 / ticks),
            ticks,
            npcs.size,
        )
        // Read cached squad assessments only. Diagnostics must not request areas, raycasts
        // or routes, or change an NPC's behavior while trying to measure it.
        val relocating = npcs.filter { npc ->
            val squad = npc.currentSquad() ?: return@filter false
            val view = squad.tactics.snapshot ?: return@filter false
            val home = view.home ?: return@filter false
            squad.order == SquadOrder.DEFEND && view.stamp == squad.orderStamp &&
                !CombatPosition.withinArea(home, npc.position(), CombatPosition.defendRadius(npc.npcClass, squad.members.size))
        }
        DebugFlags.log(
            LogGroup.PERF,
            "combat: targets={} firedLast40Ticks={} defendOutsideArea={} outsideTargets={} outsideFiredLast40Ticks={}",
            npcs.count { it.target?.isAlive == true },
            npcs.count { it.firedRecently(40) },
            relocating.size,
            relocating.count { it.target?.isAlive == true },
            relocating.count { it.firedRecently(40) },
        )
        tickNanos = 0
        ticks = 0
    }

    /**
     * Client half: how many of this mod's NPCs the render thread actually drew, and what the
     * frames cost while it did. A frame time that tracks the NPC count is the answer; one that
     * doesn't means the cost is somewhere else entirely and the NPCs are a red herring.
     */
    object Client {
        private var rendered = 0
        private var weapons = 0
        private var frames = 0
        private var lastReport = 0L

        /** Counted by [com.sbwnpc.squad.client.renderer.NpcItemInHandLayer] for every NPC close
         *  enough to still have its weapon drawn. Without it, "NPCs rendered" alone cannot tell a
         *  crowd in the distance from the same crowd walked up to, which is the difference that
         *  actually costs anything. */
        fun countWeaponDrawn() {
            if (DebugFlags.on(LogGroup.PERF)) weapons++
        }

        fun onRenderNpc(entity: net.minecraft.world.entity.LivingEntity) {
            if (!DebugFlags.on(LogGroup.PERF)) return
            if (entity is NpcEntity) rendered++
        }

        fun onRenderGui() {
            if (!DebugFlags.on(LogGroup.PERF)) return
            frames++
            val now = System.currentTimeMillis()
            if (lastReport == 0L) {
                lastReport = now
                return
            }
            val elapsed = now - lastReport
            if (elapsed < REPORT_INTERVAL_MS) return
            val perFrame = frames.coerceAtLeast(1).toDouble()
            DebugFlags.log(
                LogGroup.PERF,
                "client: {} fps, {} NPC renders per frame ({} with weapons)",
                "%.1f".format(frames * 1000.0 / elapsed),
                "%.1f".format(rendered / perFrame),
                "%.1f".format(weapons / perFrame),
            )
            lastReport = now
            frames = 0
            rendered = 0
            weapons = 0
        }
    }
}
