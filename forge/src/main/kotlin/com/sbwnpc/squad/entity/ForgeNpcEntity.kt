package com.sbwnpc.squad.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

/** [NpcEntity] with Forge's swim attribute. [NpcRegistry] bookkeeping is done from the join and leave events (see ForgeEvents). */
class ForgeNpcEntity(type: EntityType<out NpcEntity>, level: Level) : NpcEntity(type, level) {
    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        // Saved attributes carry the old base; NPCs from before the change swim at the new pace too.
        ForgeNpcAttributes.restoreSavedSwimSpeed(this)
    }
}
