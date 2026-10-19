package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.block.BarracksBlock
import com.sbwnpc.squad.block.SupplyBlock
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.registries.DeferredRegister

object ModBlocks {
    val REGISTRY: DeferredRegister<Block> = DeferredRegister.create(BuiltInRegistries.BLOCK, ModIdentity.MODID)

    @JvmField
    val BARRACKS = REGISTRY.register("barracks") { -> BarracksBlock() }

    @JvmField
    val SUPPLY = REGISTRY.register("supply") { -> SupplyBlock() }
}
