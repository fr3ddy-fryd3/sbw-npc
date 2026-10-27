package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.FabricSupplySpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A bed or an anchor replaces a Supply spawn, the way one bed replaces another; Fabric has no event for it. */
@Mixin(ServerPlayer.class)
public abstract class RespawnPositionMixin {

    @Inject(method = "setRespawnPosition(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;FZZ)V", at = @At("HEAD"))
    private void sbwnpc$spawnChanged(ResourceKey<Level> dimension, BlockPos pos, float angle, boolean force, boolean showMessage, CallbackInfo ci) {
        FabricSupplySpawns.onSetSpawn((ServerPlayer) (Object) this, pos);
    }
}
