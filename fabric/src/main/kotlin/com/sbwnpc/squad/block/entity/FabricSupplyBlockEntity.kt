package com.sbwnpc.squad.block.entity

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/** [SupplyBlockEntity] on Fabric; the load hook that puts the supply point on its side's map is FabricEvents'. */
class FabricSupplyBlockEntity(pos: BlockPos, state: BlockState) : SupplyBlockEntity(pos, state)
