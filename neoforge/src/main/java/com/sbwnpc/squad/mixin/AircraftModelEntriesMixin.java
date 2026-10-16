package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.VehicleModelEntry;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.sbwnpc.squad.integration.sbw.client.AircraftLods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(value = VehicleEntity.class, remap = false)
public abstract class AircraftModelEntriesMixin {
    @Inject(method = "getModelEntries", at = @At("RETURN"), cancellable = true)
    private void sbwnpc$addAircraftLods(CallbackInfoReturnable<List<VehicleModelEntry>> cir) {
        cir.setReturnValue(AircraftLods.augment((VehicleEntity) (Object) this, cir.getReturnValue()));
    }
}
