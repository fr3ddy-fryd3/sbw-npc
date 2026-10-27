package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.FabricWorldEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric has no event for vanilla game events (steps, landings), which NeoForge posts; this hands
 * them to {@link FabricWorldEvents#onGameEvent} the same way.
 */
@Mixin(ServerLevel.class)
public abstract class GameEventMixin {

    @Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V", at = @At("HEAD"))
    private void sbwnpc$footsteps(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
        FabricWorldEvents.onGameEvent((ServerLevel) (Object) this, event, context.sourceEntity());
    }
}
