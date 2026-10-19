package com.sbwnpc.squad.init

import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import java.util.function.Supplier

/** The shared code's handles on the mod's block entity types, bound by the loader as it registers them. */
object ModBlockEntities {
    private lateinit var barracks: Supplier<BlockEntityType<BarracksBlockEntity>>
    private lateinit var supply: Supplier<BlockEntityType<SupplyBlockEntity>>

    val BARRACKS: Supplier<BlockEntityType<BarracksBlockEntity>> = Supplier { barracks.get() }
    val SUPPLY: Supplier<BlockEntityType<SupplyBlockEntity>> = Supplier { supply.get() }

    fun bind(barracks: Supplier<BlockEntityType<BarracksBlockEntity>>, supply: Supplier<BlockEntityType<SupplyBlockEntity>>) {
        this.barracks = barracks
        this.supply = supply
    }
}
