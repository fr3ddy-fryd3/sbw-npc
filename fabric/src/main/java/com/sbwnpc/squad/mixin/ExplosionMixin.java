package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.FabricWorldEvents;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric has no explosion event; once an explosion has run, NPCs near it are suppressed and it is heard, as on NeoForge. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Shadow
    @Final
    private Level level;

    @Inject(method = "explode()V", at = @At("TAIL"))
    private void sbwnpc$detonated(CallbackInfo ci) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) FabricWorldEvents.onExplosion(serverLevel, (Explosion) (Object) this);
    }
}
