package com.sbwnpc.squad.combat

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.gameevent.GameEvent
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.VanillaGameEvent

/**
 * Footsteps of players and NPCs, off the same vanilla game events a sculk sensor listens to,
 * handed to [Hearing] — which passes them only to the other side, so a squad never hears itself.
 */
@EventBusSubscriber
object FootstepHearing {
    @SubscribeEvent
    fun onGameEvent(event: VanillaGameEvent) {
        val walker = when (val cause = event.cause) {
            is ServerPlayer -> cause.takeUnless { it.isCreative || it.isSpectator }
            is com.sbwnpc.squad.entity.NpcEntity -> cause
            else -> null
        } ?: return
        val kind = event.vanillaEvent.value()
        val landing = kind == GameEvent.HIT_GROUND.value()
        if (kind != GameEvent.STEP.value() && !landing) return
        val level = event.level as? ServerLevel ?: return
        Hearing.footstep(level, walker, landing)
    }
}
