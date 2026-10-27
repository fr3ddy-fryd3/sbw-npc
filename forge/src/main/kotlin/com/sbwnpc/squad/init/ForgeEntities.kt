package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.entity.ForgeNpcAttributes
import com.sbwnpc.squad.entity.ForgeNpcEntity
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraftforge.event.entity.EntityAttributeCreationEvent
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

/** Registers the mod's entity types and binds the shared [ModEntities] handles. */
object ForgeEntities {
    private const val WIDTH = 0.6f
    private const val HEIGHT = 1.95f
    private const val TRACKING_CHUNKS = 16
    private const val UPDATE_INTERVAL = 3

    val REGISTRY: DeferredRegister<EntityType<*>> = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ModIdentity.MODID)

    @JvmField
    val NPC: RegistryObject<EntityType<NpcEntity>> = REGISTRY.register("npc") {
        EntityType.Builder.of<NpcEntity>({ type, level -> ForgeNpcEntity(type, level) }, MobCategory.CREATURE)
            .sized(WIDTH, HEIGHT)
            // In chunks. Only the fallback: the range actually used is the server config's npcViewDistance.
            .clientTrackingRange(TRACKING_CHUNKS)
            .updateInterval(UPDATE_INTERVAL)
            .build("npc")
    }

    init {
        ModEntities.bind(NPC)
    }

    fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(NPC.get(), ForgeNpcAttributes.create().build())
    }
}
