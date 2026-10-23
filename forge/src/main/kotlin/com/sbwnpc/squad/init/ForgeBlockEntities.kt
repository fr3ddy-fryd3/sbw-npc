package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.block.entity.ForgeSupplyBlockEntity
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

/** Registers the mod's block entity types and binds the shared [ModBlockEntities] handles. */
object ForgeBlockEntities {
    val REGISTRY: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ModIdentity.MODID)

    @JvmField
    val BARRACKS: RegistryObject<BlockEntityType<BarracksBlockEntity>> = REGISTRY.register("barracks") {
        BlockEntityType.Builder.of({ pos, state -> BarracksBlockEntity(pos, state) }, ModBlocks.BARRACKS.get()).build(null)
    }

    @JvmField
    val SUPPLY: RegistryObject<BlockEntityType<SupplyBlockEntity>> = REGISTRY.register("supply") {
        BlockEntityType.Builder.of<SupplyBlockEntity>({ pos, state -> ForgeSupplyBlockEntity(pos, state) }, ModBlocks.SUPPLY.get()).build(null)
    }

    init {
        ModBlockEntities.bind(BARRACKS, SUPPLY)
    }
}
