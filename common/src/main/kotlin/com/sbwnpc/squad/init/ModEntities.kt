package com.sbwnpc.squad.init

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.EntityType
import java.util.function.Supplier

/** The shared code's handles on the mod's entity types, bound by the loader as it registers them. */
object ModEntities {
    private lateinit var npc: Supplier<EntityType<NpcEntity>>

    val NPC: Supplier<EntityType<NpcEntity>> = Supplier { npc.get() }

    fun bind(npc: Supplier<EntityType<NpcEntity>>) {
        this.npc = npc
    }
}
