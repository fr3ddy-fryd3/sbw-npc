package com.sbwnpc.squad.combat

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.gameevent.GameEvent
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.VanillaGameEvent

/**
 * Players' footsteps, off the same vanilla game events a sculk sensor listens to, handed to
 * [Hearing]. Only players: NPCs on patrol would otherwise hear each other without end.
 */
@EventBusSubscriber
object FootstepHearing {
    @SubscribeEvent
    fun onGameEvent(event: VanillaGameEvent) {
        val player = event.cause as? ServerPlayer ?: return
        if (player.isCreative || player.isSpectator) return
        val kind = event.vanillaEvent.value()
        val landing = kind == GameEvent.HIT_GROUND.value()
        if (kind != GameEvent.STEP.value() && !landing) return
        val level = event.level as? ServerLevel ?: return
        Hearing.footstep(level, player, landing)
    }
}
