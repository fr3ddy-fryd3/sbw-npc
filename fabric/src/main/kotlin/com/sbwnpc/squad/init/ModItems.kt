package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.item.SquadToolItem
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item

/** The mod's items, registered when this object is first touched ([init]); blocks come first. */
object ModItems {
    val SQUAD_TOOL: Item = Registry.register(BuiltInRegistries.ITEM, ModIdentity.loc("squad_tool"), SquadToolItem())
    val BARRACKS: Item = Registry.register(BuiltInRegistries.ITEM, ModIdentity.loc("barracks"), BlockItem(ModBlocks.BARRACKS, Item.Properties()))
    val SUPPLY: Item = Registry.register(BuiltInRegistries.ITEM, ModIdentity.loc("supply"), BlockItem(ModBlocks.SUPPLY, Item.Properties()))

    fun init() = Unit
}
