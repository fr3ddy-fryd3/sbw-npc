package com.sbwnpc.squad.init

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.minecraft.world.item.CreativeModeTabs

@EventBusSubscriber
object ModCreativeTab {
    @SubscribeEvent
    fun onBuildContents(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.NPC_SPAWN_EGG.get())
        }
        if (event.tabKey == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.SQUAD_TOOL.get())
        }
        // Registered but never added here — the Barracks BlockItem was completely unreachable in
        // survival/creative (no recipe either). Real bug, not a design choice.
        if (event.tabKey == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.BARRACKS.get())
        }
    }
}
