package com.sbwnpc.squad

import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.init.ModItems
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.init.ModSensors
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
        ModBlocks.REGISTRY.register(bus)
        ModBlockEntities.REGISTRY.register(bus)
        // Forces ModSensors'/ModMemories' static fields (and the SmartBrainLib sensor/memory
        // registration side-effects they trigger) to run now, before the registry closes — same
        // trick the library's own SBLSensors/SBLMemoryTypes use themselves.
        ModSensors.init()
        ModMemories.init()
    }

    companion object {
        const val MODID = "sbwnpc"
        val LOGGER = LogManager.getLogger(MODID)

        fun loc(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MODID, path)
    }
}
