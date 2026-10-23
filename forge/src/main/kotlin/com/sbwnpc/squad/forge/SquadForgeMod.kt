package com.sbwnpc.squad.forge

import com.sbwnpc.squad.ForgeEvents
import com.sbwnpc.squad.ForgeWorldEvents
import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.combat.BuildFlags
import com.sbwnpc.squad.config.DebugConfig
import com.sbwnpc.squad.config.Settings
import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.init.ForgeBlockEntities
import com.sbwnpc.squad.init.ForgeEntities
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModItems
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.init.ModSensors
import com.sbwnpc.squad.integration.sbw.SbwCreativeTab
import com.sbwnpc.squad.integration.sbw.SbwDrones
import com.sbwnpc.squad.integration.sbw.SbwGear
import com.sbwnpc.squad.integration.sbw.SbwGrenades
import com.sbwnpc.squad.integration.sbw.SbwGuns
import com.sbwnpc.squad.integration.sbw.SbwHearing
import com.sbwnpc.squad.integration.sbw.SbwMortars
import com.sbwnpc.squad.integration.sbw.SbwPlayerSupply
import com.sbwnpc.squad.integration.sbw.SbwVehicles
import com.sbwnpc.squad.network.ClientboundPackets
import com.sbwnpc.squad.network.ForgeNetwork
import com.sbwnpc.squad.network.ServerboundPackets
import com.sbwnpc.squad.platform.ForgeLoaderHooks
import net.minecraftforge.api.distmarker.Dist
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import net.minecraftforge.event.entity.EntityAttributeCreationEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.event.config.ModConfigEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import java.util.function.Consumer

/** Forge entry point: registers content, wires the ports to SuperbWarfare for 1.20.1 and hooks the events. */
@Mod(ModIdentity.MODID)
class SquadForgeMod {
    init {
        ModIdentity.LOGGER.info("SBW Squads initializing (Forge)")
        val modBus = MOD_BUS

        Ports.guns = SbwGuns
        Ports.vehicles = SbwVehicles
        Ports.mortars = SbwMortars
        Ports.grenades = SbwGrenades
        Ports.playerSupply = SbwPlayerSupply
        Ports.drones = SbwDrones
        Ports.gear = SbwGear
        Ports.loader = ForgeLoaderHooks
        ClientboundPackets.transport = ForgeNetwork
        ServerboundPackets.transport = ForgeNetwork
        Settings.current = SquadConfig

        val context = ModLoadingContext.get()
        context.registerConfig(ModConfig.Type.SERVER, SquadConfig.SPEC)
        if (BuildFlags.DEBUG_ENABLED) {
            context.registerConfig(ModConfig.Type.COMMON, DebugConfig.SPEC, DebugConfig.FILE)
            modBus.addListener(Consumer<ModConfigEvent>(DebugConfig::onConfig))
        }

        ForgeEntities.REGISTRY.register(modBus)
        ModItems.ITEMS.register(modBus)
        ModBlocks.REGISTRY.register(modBus)
        ForgeBlockEntities.REGISTRY.register(modBus)
        modBus.addListener(Consumer<EntityAttributeCreationEvent>(ForgeEntities::registerAttributes))
        // Forces the sensor and memory types' static fields (and the SmartBrainLib registration they
        // trigger) to run now, before the registries freeze.
        ModSensors.init()
        ModMemories.init()

        ForgeNetwork.register()
        ForgeEvents.register()
        ForgeWorldEvents.register()
        SbwHearing.register()
        SbwCreativeTab.register(modBus)

        if (FMLEnvironment.dist == Dist.CLIENT) SquadForgeClient.register(modBus)
    }
}
