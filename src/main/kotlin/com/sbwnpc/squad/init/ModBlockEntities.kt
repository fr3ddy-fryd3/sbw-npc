package com.sbwnpc.squad.init

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.neoforge.registries.DeferredRegister

object ModBlockEntities {
    val REGISTRY: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, SquadMod.MODID)

    @JvmField
    val BARRACKS = REGISTRY.register("barracks") { ->
        BlockEntityType.Builder.of(
            { pos, state -> BarracksBlockEntity(pos, state) },
            ModBlocks.BARRACKS.get()
        ).build(null)
    }
}
