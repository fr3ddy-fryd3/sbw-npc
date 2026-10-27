package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.init.ModItems
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/**
 * The squad items must be obtainable the way a player looks for them: in SuperbWarfare's *Items*
 * creative tab, which is also what a recipe viewer such as JEI lists. The addon ships no JEI plugin
 * of its own; JEI shows every item the creative tabs contain.
 */
@PrefixGameTestTemplate(false)
object ItemAvailabilityGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 20, batch = "item-availability")
    fun squadItemsAreRegisteredAndListedInSbwItemsTab(helper: GameTestHelper) {
        val squadItems = mapOf("squad_tool" to ModItems.SQUAD_TOOL.get(), "barracks" to ModItems.BARRACKS.get(), "supply" to ModItems.SUPPLY.get())
        squadItems.forEach { (path, item) ->
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(item) == ResourceLocation.fromNamespaceAndPath("sbwnpc", path), "$path is not registered as sbwnpc:$path")
        }

        CreativeModeTabs.tryRebuildTabContents(helper.level.enabledFeatures(), true, helper.level.registryAccess())
        val tab = requireNotNull(BuiltInRegistries.CREATIVE_MODE_TAB.get(ResourceLocation.fromNamespaceAndPath("superbwarfare", "item"))) { "SuperbWarfare's Items tab is missing" }
        val listed = tab.displayItems.map { it.item }.toSet()
        squadItems.forEach { (path, item) ->
            helper.assertTrue(item in listed, "sbwnpc:$path is missing from SuperbWarfare's Items creative tab")
        }
        helper.succeed()
    }
}
