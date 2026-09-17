package com.sbwnpc.squad.init

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

@EventBusSubscriber
object ModEntities {
    val REGISTRY: DeferredRegister<EntityType<*>> = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, SquadMod.MODID)

    @JvmField
    val NPC: DeferredHolder<EntityType<*>, EntityType<NpcEntity>> = REGISTRY.register("npc") { ->
        EntityType.Builder.of(::NpcEntity, MobCategory.CREATURE)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.74f)
            // In CHUNKS, not blocks (vanilla multiplies by 16): 48 meant "track from 768 blocks",
            // i.e. effectively "every NPC within the server view distance is synced to every player
            // in it". 8 chunks (128 blocks) is what vanilla uses for monsters, and matches
            // OffscreenFire.WITNESS_RADIUS — beyond that nobody sees the NPC, so nobody needs its
            // position/rotation/equipment packets 6-7 times a second.
            .setTrackingRange(8)
            .setUpdateInterval(3)
            .build("npc")
    }

    @SubscribeEvent
    fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(NPC.get(), NpcEntity.createAttributes().build())
    }
}
