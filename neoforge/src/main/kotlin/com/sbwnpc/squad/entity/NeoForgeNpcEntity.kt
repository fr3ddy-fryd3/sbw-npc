package com.sbwnpc.squad.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

/** [NpcEntity] with the hooks only NeoForge has: its level lifecycle and its swim attribute. */
class NeoForgeNpcEntity(type: EntityType<out NpcEntity>, level: Level) : NpcEntity(type, level) {
    // NpcRegistry bookkeeping — see that object. NeoForge's lifecycle hooks, so this covers every
    // add/remove path (spawn, chunk load/unload, death, /kill, dimension change) without needing a
    // separate event subscriber.
    override fun onAddedToLevel() {
        super.onAddedToLevel()
        // Chunk loading calls this hook even when EntityJoinLevelEvent cancelled the addition.
        if (!isRemoved) NpcRegistry.add(this)
    }

    override fun onRemovedFromLevel() {
        if (!level().isClientSide) com.sbwnpc.squad.entity.ai.SquadMarch.forgetIndividual(uuid)
        NpcRegistry.remove(this)
        super.onRemovedFromLevel()
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        // Saved attributes carry the old base; NPCs from before the change swim at the new pace too.
        NeoForgeNpcAttributes.restoreSavedSwimSpeed(this)
    }
}
