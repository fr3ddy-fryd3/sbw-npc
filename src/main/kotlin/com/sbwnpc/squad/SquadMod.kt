package com.sbwnpc.squad

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import org.apache.logging.log4j.LogManager

@Mod(SquadMod.MODID)
class SquadMod(bus: IEventBus, container: ModContainer) {
    init {
        LOGGER.info("SBW Squads initializing")
    }

    companion object {
        const val MODID = "sbwnpc"
        val LOGGER = LogManager.getLogger(MODID)
    }
}
