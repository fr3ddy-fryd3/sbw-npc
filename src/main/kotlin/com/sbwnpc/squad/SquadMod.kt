package com.sbwnpc.squad

import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.init.ModItems
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import org.apache.logging.log4j.LogManager

@Mod(SquadMod.MODID)
class SquadMod(bus: IEventBus, container: ModContainer) {
    init {
        LOGGER.info("SBW Squads initializing")

        ModEntities.REGISTRY.register(bus)
        ModItems.ITEMS.register(bus)
    }

    companion object {
        const val MODID = "sbwnpc"
        val LOGGER = LogManager.getLogger(MODID)

        fun loc(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MODID, path)
    }
}
