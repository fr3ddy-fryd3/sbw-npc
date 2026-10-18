package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.neoforge.registries.DeferredRegister

/** Registers the mod's block entity types with NeoForge and binds the shared [ModBlockEntities] handles. */
object NeoForgeBlockEntities {
    val REGISTRY: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModIdentity.MODID)

    @JvmField
    val BARRACKS = REGISTRY.register("barracks") { ->
        BlockEntityType.Builder.of(
            { pos, state -> BarracksBlockEntity(pos, state) },
            ModBlocks.BARRACKS.get(),
        ).build(null)
    }

    @JvmField
    val SUPPLY = REGISTRY.register("supply") { ->
        BlockEntityType.Builder.of(
            { pos, state -> SupplyBlockEntity(pos, state) },
            ModBlocks.SUPPLY.get(),
        ).build(null)
    }

    init {
        ModBlockEntities.bind(BARRACKS, SUPPLY)
    }
}
