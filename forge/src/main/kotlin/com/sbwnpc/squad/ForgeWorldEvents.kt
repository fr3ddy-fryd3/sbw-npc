package com.sbwnpc.squad

import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.integration.sbw.SbwHearing
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.VanillaGameEvent
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.eventbus.api.EventPriority
import java.util.function.Consumer

/** Vanilla game events, explosions and spawn changes, handed to the shared hearing and spawn code. */
object ForgeWorldEvents {
    private const val SUPPRESSION_RADIUS = 6.0

    fun register() {
        val bus = MinecraftForge.EVENT_BUS
        bus.addListener(EventPriority.NORMAL, false, VanillaGameEvent::class.java, Consumer(::onGameEvent))
        bus.addListener(EventPriority.NORMAL, false, ExplosionEvent.Detonate::class.java, Consumer(::onExplosion))
        bus.addListener(EventPriority.NORMAL, false, PlayerSetSpawnEvent::class.java, Consumer(ForgeSupplySpawns::onSetSpawn))
    }

    /**
     * Footsteps and landings of players and NPCs, off the same vanilla game events a sculk sensor
     * listens to, handed to [Hearing] — which passes them only to the other side.
     */
    private fun onGameEvent(event: VanillaGameEvent) {
        val walker = when (val cause = event.cause) {
            is ServerPlayer -> cause.takeUnless { it.isCreative || it.isSpectator }
            is NpcEntity -> cause
            else -> null
        } ?: return
        val kind = event.vanillaEvent
        val landing = kind == GameEvent.HIT_GROUND
        if (kind != GameEvent.STEP && !landing) return
        val level = event.level as? ServerLevel ?: return
        Hearing.footstep(level, walker, landing)
    }

    /** Every NPC near an explosion is suppressed, hit or not, and the blast is heard. */
    private fun onExplosion(event: ExplosionEvent.Detonate) {
        val level = event.level as? ServerLevel ?: return
        val pos = event.explosion.position
        NpcRegistry.forEachWithin(level, pos, SUPPRESSION_RADIUS) { it.suppress(pos) }
        SbwHearing.onExplosion(level, event.explosion)
    }
}
