package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.entity.FabricNpcEntity
import com.sbwnpc.squad.entity.NpcEntity
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import java.util.function.Supplier

/** Registers the mod's entity types and binds the shared [ModEntities] handles. */
object FabricEntities {
    private const val WIDTH = 0.6f
    private const val HEIGHT = 1.95f
    private const val EYE_HEIGHT = 1.74f
    private const val TRACKING_CHUNKS = 16
    private const val UPDATE_INTERVAL = 3

    val NPC: EntityType<NpcEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        ModIdentity.loc("npc"),
        EntityType.Builder.of<NpcEntity>({ type, level -> FabricNpcEntity(type, level) }, MobCategory.CREATURE)
            .sized(WIDTH, HEIGHT)
            .eyeHeight(EYE_HEIGHT)
            // In chunks. Only the fallback: the range actually used is the server config's npcViewDistance.
            .clientTrackingRange(TRACKING_CHUNKS)
            .updateInterval(UPDATE_INTERVAL)
            .build("npc"),
    )

    fun init() {
        ModEntities.bind(Supplier { NPC })
        FabricDefaultAttributeRegistry.register(NPC, NpcEntity.createAttributes())
    }
}
