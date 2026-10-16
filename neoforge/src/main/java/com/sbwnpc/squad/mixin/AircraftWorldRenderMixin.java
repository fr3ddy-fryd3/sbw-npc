package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.client.renderer.SyncedEntityWorldRenderer;
import com.sbwnpc.squad.integration.sbw.client.SbwAircraftRendering;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adapt the existing SBW pass instead of adding a second BVR renderer or network protocol. */
@Mixin(value = SyncedEntityWorldRenderer.class, remap = false)
public abstract class AircraftWorldRenderMixin {
    @Inject(method = "onRenderLevelStage", at = @At("HEAD"), cancellable = true)
    private void sbwnpc$renderAircraftAtRange(RenderLevelStageEvent event, CallbackInfo ci) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        SbwAircraftRendering.render(event);
        ci.cancel();
    }
}
