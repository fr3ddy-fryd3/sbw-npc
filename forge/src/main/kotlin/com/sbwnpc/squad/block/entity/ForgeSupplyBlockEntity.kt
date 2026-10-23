package com.sbwnpc.squad.block.entity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState

/** [SupplyBlockEntity] with Forge's load hook, which puts the supply point back on its side's map. */
class ForgeSupplyBlockEntity(pos: BlockPos, state: BlockState) : SupplyBlockEntity(pos, state) {
    override fun onLoad() {
        super.onLoad()
        val level = level as? ServerLevel ?: return
        SupplyPoints.get(level).remember(blockPos, faction)
    }
}
