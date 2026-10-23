package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.block.BarracksBlock
import com.sbwnpc.squad.block.SupplyBlock
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block

/** The mod's blocks, registered when this object is first touched ([init]). */
object ModBlocks {
    val BARRACKS: Block = Registry.register(BuiltInRegistries.BLOCK, ModIdentity.loc("barracks"), BarracksBlock())
    val SUPPLY: Block = Registry.register(BuiltInRegistries.BLOCK, ModIdentity.loc("supply"), SupplyBlock())

    fun init() = Unit
}
