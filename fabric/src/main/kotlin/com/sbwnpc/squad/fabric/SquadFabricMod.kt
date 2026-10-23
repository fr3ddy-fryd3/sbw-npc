package com.sbwnpc.squad.fabric

import com.sbwnpc.squad.FabricEvents
import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.combat.BuildFlags
import com.sbwnpc.squad.config.DebugConfig
import com.sbwnpc.squad.config.Settings
import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.init.FabricBlockEntities
import com.sbwnpc.squad.init.FabricEntities
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModItems
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.init.ModSensors
import com.sbwnpc.squad.integration.sbw.SbwDrones
import com.sbwnpc.squad.integration.sbw.SbwGear
import com.sbwnpc.squad.integration.sbw.SbwGrenades
import com.sbwnpc.squad.integration.sbw.SbwGuns
import com.sbwnpc.squad.integration.sbw.SbwHearing
import com.sbwnpc.squad.integration.sbw.SbwMortars
import com.sbwnpc.squad.integration.sbw.SbwPlayerSupply
import com.sbwnpc.squad.integration.sbw.SbwVehicles
import com.sbwnpc.squad.network.ClientboundPackets
import com.sbwnpc.squad.network.FabricClientboundTransport
import com.sbwnpc.squad.network.FabricNetwork
import com.sbwnpc.squad.platform.FabricLoaderHooks
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents
import net.fabricmc.api.ModInitializer
import net.neoforged.fml.config.ModConfig

/** Fabric entry point: registers content, wires the ports to SuperbWarfare ReFabricated and hooks the events. */
class SquadFabricMod : ModInitializer {
    override fun onInitialize() {
        ModIdentity.LOGGER.info("SBW Squads initializing (Fabric)")

        Ports.guns = SbwGuns
        Ports.vehicles = SbwVehicles
        Ports.mortars = SbwMortars
        Ports.grenades = SbwGrenades
        Ports.playerSupply = SbwPlayerSupply
        Ports.drones = SbwDrones
        Ports.gear = SbwGear
        Ports.loader = FabricLoaderHooks
        ClientboundPackets.transport = FabricClientboundTransport
        Settings.current = SquadConfig

        NeoForgeConfigRegistry.INSTANCE.register(ModIdentity.MODID, ModConfig.Type.SERVER, SquadConfig.SPEC)
        if (BuildFlags.DEBUG_ENABLED) {
            NeoForgeConfigRegistry.INSTANCE.register(ModIdentity.MODID, ModConfig.Type.COMMON, DebugConfig.SPEC, DebugConfig.FILE)
            NeoForgeModConfigEvents.loading(ModIdentity.MODID).register(DebugConfig::onConfig)
            NeoForgeModConfigEvents.reloading(ModIdentity.MODID).register(DebugConfig::onConfig)
        }

        ModBlocks.init()
        ModItems.init()
        FabricBlockEntities.init()
        FabricEntities.init()
        // Forces the sensor and memory types' static fields (and the SmartBrainLib registration they
        // trigger) to run now, before the registries freeze.
        ModSensors.init()
        ModMemories.init()

        FabricNetwork.register()
        FabricEvents.register()
        SbwHearing.register()
        com.sbwnpc.squad.integration.sbw.SbwCreativeTab.register()
    }
}
