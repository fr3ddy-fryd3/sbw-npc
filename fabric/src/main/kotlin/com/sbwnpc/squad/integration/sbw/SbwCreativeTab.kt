package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.init.ModTabs
import com.sbwnpc.squad.init.ModItems
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents

/**
 * Everything goes into SuperbWarfare's own *Items* tab rather than the vanilla ones: this is an
 * SBW addon, and a player looking for the squad terminal looks where the rest of SBW's kit is,
 * not in vanilla Combat and Functional Blocks two tabs apart.
 */
object SbwCreativeTab {
    fun register() {
        // Matched when the tab is built, not looked up now: our initializer can run before SBW has registered its tab.
        ItemGroupEvents.MODIFY_ENTRIES_ALL.register { group, entries ->
            if (group === ModTabs.ITEM_TAB) {
                entries.accept(ModItems.SQUAD_TOOL)
                entries.accept(ModItems.BARRACKS)
                entries.accept(ModItems.SUPPLY)
            }
        }
    }
}
