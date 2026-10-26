package com.sbwnpc.squad.gametest

import net.minecraft.world.level.GameRules
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStartedEvent

/**
 * Holds every scene in full daylight.
 *
 * A man notices an enemy by the light at him (see `SpottingTime.Light`). The server clock kept running
 * across a long batch, so scenes late in it ran at dusk, where an enemy 52 blocks off is past the
 * cut-off: `machineGunnerFiresFromReservedTower` passed alone and timed out after the other overwatch
 * scenes (#175). Scenes that need another hour set it and put it back themselves.
 */
@EventBusSubscriber(modid = "sbwnpc")
object GameTestDaylight {
    private const val NOON = 6000L

    @SubscribeEvent
    fun hold(event: ServerStartedEvent) {
        val server = event.server
        server.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
        server.allLevels.forEach { it.setDayTime(NOON) }
    }
}
