package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.init.ModTabs
import com.sbwnpc.squad.init.ModItems
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Everything goes into SuperbWarfare's own *Items* tab rather than the vanilla ones: this is an
 * SBW addon, and a player looking for the squad terminal looks where the rest of SBW's kit is,
 * not in vanilla Combat and Functional Blocks two tabs apart.
 */
object SbwCreativeTab {
    fun register() {
        val tab = BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(ModTabs.ITEM_TAB).orElse(null) ?: return
        ItemGroupEvents.modifyEntriesEvent(tab).register { entries ->
            entries.accept(ModItems.SQUAD_TOOL)
            entries.accept(ModItems.BARRACKS)
            entries.accept(ModItems.SUPPLY)
        }
    }
}
