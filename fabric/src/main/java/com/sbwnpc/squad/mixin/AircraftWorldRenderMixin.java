package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.client.renderer.SyncedEntityWorldRenderer;
import com.sbwnpc.squad.integration.sbw.client.SbwAircraftRendering;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adapt the existing SBW pass instead of adding a second BVR renderer or network protocol. ReFabricated
 * registers {@code render} on {@code WorldRenderEvents.AFTER_ENTITIES}, the stage NeoForge's version is
 * limited to, so the replacement runs at the same point.
 */
@Mixin(value = SyncedEntityWorldRenderer.class, remap = false)
public abstract class AircraftWorldRenderMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void sbwnpc$renderAircraftAtRange(WorldRenderContext context, CallbackInfo ci) {
        SbwAircraftRendering.render(context);
        ci.cancel();
    }
}
