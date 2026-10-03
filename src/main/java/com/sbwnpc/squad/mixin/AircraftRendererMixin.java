package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.VehicleModelEntry;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sbwnpc.squad.integration.sbw.client.AircraftLods;
import com.sbwnpc.squad.integration.sbw.client.AircraftVisibility;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GeoVehicleRenderer.class, remap = false)
public abstract class AircraftRendererMixin {
    @Inject(
        method = "shouldRender(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
        at = @At("HEAD"), cancellable = true
    )
    private void sbwnpc$deferAircraftToBvr(VehicleEntity entity, Frustum frustum,
            double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (AircraftVisibility.usesBvr(entity)) cir.setReturnValue(false);
    }

    @Inject(method = "getCurrentModelEntry", at = @At("HEAD"), cancellable = true)
    private void sbwnpc$selectFarthestAircraftLod(PoseStack stack, VehicleEntity entity,
            CallbackInfoReturnable<VehicleModelEntry> cir) {
        if (AircraftVisibility.isAircraft(entity)) cir.setReturnValue(AircraftLods.select(entity, entity.getModelEntries(), stack));
    }
}
