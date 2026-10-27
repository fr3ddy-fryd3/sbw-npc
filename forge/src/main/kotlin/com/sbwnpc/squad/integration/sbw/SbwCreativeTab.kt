package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.init.ModTabs
import com.sbwnpc.squad.init.ModItems
import net.minecraft.world.item.CreativeModeTab
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.eventbus.api.IEventBus
import java.util.function.Consumer

/**
 * Everything goes into SuperbWarfare's own *Items* tab rather than the vanilla ones: this is an
 * SBW addon, and a player looking for the squad terminal looks where the rest of SBW's kit is,
 * not in vanilla Combat and Functional Blocks two tabs apart.
 */
object SbwCreativeTab {
    fun register(modBus: IEventBus) {
        modBus.addListener(Consumer<BuildCreativeModeTabContentsEvent> { event ->
            if (event.tabKey == ModTabs.ITEM_TAB.key) {
                event.accept(ModItems.SQUAD_TOOL.get())
                event.accept(ModItems.BARRACKS.get())
                event.accept(ModItems.SUPPLY.get())
            }
        })
    }
}
