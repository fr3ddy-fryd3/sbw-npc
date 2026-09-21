package com.sbwnpc.squad.init

import com.atsuishio.superbwarfare.init.ModTabs
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent

/**
 * Everything goes into SuperbWarfare's own *Items* tab rather than the vanilla ones: this is an
 * SBW addon, and a player looking for the squad terminal looks where the rest of SBW's kit is,
 * not in vanilla Combat and Functional Blocks two tabs apart.
 */
@EventBusSubscriber
object ModCreativeTab {
    @SubscribeEvent
    fun onBuildContents(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == ModTabs.ITEM_TAB.key) {
            event.accept(ModItems.SQUAD_TOOL.get())
            event.accept(ModItems.BARRACKS.get())
            event.accept(ModItems.SUPPLY.get())
        }
    }
}
