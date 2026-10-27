package com.sbwnpc.squad.integration.sbw

import com.sbwnpc.squad.init.ModItems
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/**
 * Everything goes into SuperbWarfare's own *Items* tab rather than the vanilla ones: this is an
 * SBW addon, and a player looking for the squad terminal looks where the rest of SBW's kit is,
 * not in vanilla Combat and Functional Blocks two tabs apart.
 */
object SbwCreativeTab {
    private val SBW_ITEMS = ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("superbwarfare", "item"))

    // Keyed by id, not by SBW's tab object: our initializer can run before SBW has registered its tabs.
    fun register() {
        ItemGroupEvents.modifyEntriesEvent(SBW_ITEMS).register { entries ->
            entries.accept(ModItems.SQUAD_TOOL)
            entries.accept(ModItems.BARRACKS)
            entries.accept(ModItems.SUPPLY)
        }
    }
}
