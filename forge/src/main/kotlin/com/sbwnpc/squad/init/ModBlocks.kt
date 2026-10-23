package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.block.BarracksBlock
import com.sbwnpc.squad.block.SupplyBlock
import net.minecraft.world.level.block.Block
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModBlocks {
    val REGISTRY: DeferredRegister<Block> = DeferredRegister.create(ForgeRegistries.BLOCKS, ModIdentity.MODID)

    @JvmField
    val BARRACKS: RegistryObject<Block> = REGISTRY.register("barracks") { BarracksBlock() }

    @JvmField
    val SUPPLY: RegistryObject<Block> = REGISTRY.register("supply") { SupplyBlock() }
}
