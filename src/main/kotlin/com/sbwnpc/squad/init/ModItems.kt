package com.sbwnpc.squad.init

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.item.SquadToolItem
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.neoforged.neoforge.common.DeferredSpawnEggItem
import net.neoforged.neoforge.registries.DeferredRegister

object ModItems {
    val ITEMS: DeferredRegister<Item> = DeferredRegister.create(BuiltInRegistries.ITEM, SquadMod.MODID)

    @JvmField
    val NPC_SPAWN_EGG = ITEMS.register("npc_spawn_egg") { ->
        DeferredSpawnEggItem(ModEntities.NPC, 0x4b5335, 0x2c331e, Item.Properties())
    }

    @JvmField
    val SQUAD_TOOL = ITEMS.register("squad_tool") { -> SquadToolItem() }
}
