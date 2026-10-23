package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.block.entity.FabricSupplyBlockEntity
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.entity.BlockEntityType
import java.util.function.Supplier

/** Registers the mod's block entity types and binds the shared [ModBlockEntities] handles. */
object FabricBlockEntities {
    val BARRACKS: BlockEntityType<BarracksBlockEntity> = Registry.register(
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        ModIdentity.loc("barracks"),
        FabricBlockEntityTypeBuilder.create({ pos, state -> BarracksBlockEntity(pos, state) }, ModBlocks.BARRACKS).build(),
    )

    val SUPPLY: BlockEntityType<SupplyBlockEntity> = Registry.register(
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        ModIdentity.loc("supply"),
        FabricBlockEntityTypeBuilder.create<SupplyBlockEntity>({ pos, state -> FabricSupplyBlockEntity(pos, state) }, ModBlocks.SUPPLY).build(),
    )

    fun init() {
        ModBlockEntities.bind(Supplier { BARRACKS }, Supplier { SUPPLY })
    }
}
