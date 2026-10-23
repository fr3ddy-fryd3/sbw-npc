package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.item.SquadToolItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModItems {
    val ITEMS: DeferredRegister<Item> = DeferredRegister.create(ForgeRegistries.ITEMS, ModIdentity.MODID)

    @JvmField
    val SQUAD_TOOL: RegistryObject<Item> = ITEMS.register("squad_tool") { SquadToolItem() }

    @JvmField
    val BARRACKS: RegistryObject<Item> = ITEMS.register("barracks") { BlockItem(ModBlocks.BARRACKS.get(), Item.Properties()) }

    @JvmField
    val SUPPLY: RegistryObject<Item> = ITEMS.register("supply") { BlockItem(ModBlocks.SUPPLY.get(), Item.Properties()) }
}
