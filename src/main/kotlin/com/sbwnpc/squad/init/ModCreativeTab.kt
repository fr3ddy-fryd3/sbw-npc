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
    }
}
