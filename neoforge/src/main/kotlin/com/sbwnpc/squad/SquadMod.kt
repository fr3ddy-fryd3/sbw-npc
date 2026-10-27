package com.sbwnpc.squad

import com.sbwnpc.squad.config.Settings
import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModItems
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.init.ModSensors
import com.sbwnpc.squad.init.NeoForgeBlockEntities
import com.sbwnpc.squad.init.NeoForgeEntities
import com.sbwnpc.squad.integration.sbw.SbwDrones
import com.sbwnpc.squad.integration.sbw.SbwGear
import com.sbwnpc.squad.integration.sbw.SbwGrenades
import com.sbwnpc.squad.integration.sbw.SbwGuns
import com.sbwnpc.squad.integration.sbw.SbwMortars
import com.sbwnpc.squad.integration.sbw.SbwVehicles
import com.sbwnpc.squad.network.ClientboundPackets
import com.sbwnpc.squad.network.NeoForgeClientboundTransport
import com.sbwnpc.squad.platform.NeoForgeLoaderHooks
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod

@Mod(ModIdentity.MODID)
class SquadMod(bus: IEventBus, container: ModContainer) {
    init {
        ModIdentity.LOGGER.info("SBW Squads initializing")

        Ports.guns = SbwGuns
        Ports.vehicles = SbwVehicles
        Ports.mortars = SbwMortars
        Ports.grenades = SbwGrenades
        Ports.playerSupply = com.sbwnpc.squad.integration.sbw.SbwPlayerSupply
        Ports.drones = SbwDrones
        Ports.gear = SbwGear
        Ports.loader = NeoForgeLoaderHooks
        ClientboundPackets.transport = NeoForgeClientboundTransport
        com.sbwnpc.squad.network.ServerboundPackets.transport = com.sbwnpc.squad.network.NeoForgeServerboundTransport
        Settings.current = SquadConfig

        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, com.sbwnpc.squad.config.SquadConfig.SPEC)
        if (com.sbwnpc.squad.combat.BuildFlags.DEBUG_ENABLED) {
            container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, com.sbwnpc.squad.config.DebugConfig.SPEC, com.sbwnpc.squad.config.DebugConfig.FILE)
            bus.addListener(com.sbwnpc.squad.config.DebugConfig::onConfig)
        }

        NeoForgeEntities.REGISTRY.register(bus)
        ModItems.ITEMS.register(bus)
        ModBlocks.REGISTRY.register(bus)
        NeoForgeBlockEntities.REGISTRY.register(bus)
        // Forces ModSensors'/ModMemories' static fields (and the SmartBrainLib sensor/memory
        // registration side-effects they trigger) to run now, before the registry closes — same
        // trick the library's own SBLSensors/SBLMemoryTypes use themselves.
        ModSensors.init()
        ModMemories.init()
    }
}
