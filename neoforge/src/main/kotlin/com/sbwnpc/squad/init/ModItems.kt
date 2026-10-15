package com.sbwnpc.squad.init

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.item.SquadToolItem
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.neoforged.neoforge.registries.DeferredRegister

object ModItems {
    val ITEMS: DeferredRegister<Item> = DeferredRegister.create(BuiltInRegistries.ITEM, SquadMod.MODID)

    @JvmField
    val SQUAD_TOOL = ITEMS.register("squad_tool") { -> SquadToolItem() }

    @JvmField
    val BARRACKS = ITEMS.register("barracks") { -> BlockItem(ModBlocks.BARRACKS.get(), Item.Properties()) }

    @JvmField
    val SUPPLY = ITEMS.register("supply") { -> BlockItem(ModBlocks.SUPPLY.get(), Item.Properties()) }
}
