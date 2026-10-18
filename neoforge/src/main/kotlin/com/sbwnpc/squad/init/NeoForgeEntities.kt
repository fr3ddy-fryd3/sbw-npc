package com.sbwnpc.squad.init

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/** Registers the mod's entity types with NeoForge and binds the shared [ModEntities] handles. */
@EventBusSubscriber
object NeoForgeEntities {
    val REGISTRY: DeferredRegister<EntityType<*>> = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, ModIdentity.MODID)

    @JvmField
    val NPC: DeferredHolder<EntityType<*>, EntityType<NpcEntity>> = REGISTRY.register("npc") { ->
        EntityType.Builder.of(::NpcEntity, MobCategory.CREATURE)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.74f)
            // In chunks. Only the fallback: the range actually used is the server config's
            // npcViewDistance (EntityTrackingRangeMixin).
            .setTrackingRange(16)
            .setUpdateInterval(3)
            .build("npc")
    }

    init {
        ModEntities.bind(NPC)
    }

    @SubscribeEvent
    fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(NPC.get(), NpcEntity.createAttributes().build())
    }
}
