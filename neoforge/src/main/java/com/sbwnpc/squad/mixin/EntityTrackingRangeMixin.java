package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.config.SquadConfig;
import com.sbwnpc.squad.init.ModEntities;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * How far NPCs are sent to players, from the server config rather than the fixed range the entity
 * type was registered with — registration happens long before any world's config is loaded. The
 * server reads this each time it starts tracking an entity.
 */
@Mixin(EntityType.class)
public abstract class EntityTrackingRangeMixin {

    @Inject(method = "clientTrackingRange", at = @At("HEAD"), cancellable = true)
    private void sbwnpc$npcViewDistance(CallbackInfoReturnable<Integer> cir) {
        if (ModEntities.NPC.isBound() && (Object) this == ModEntities.NPC.get()) cir.setReturnValue(SquadConfig.npcTrackingChunks());
    }
}
