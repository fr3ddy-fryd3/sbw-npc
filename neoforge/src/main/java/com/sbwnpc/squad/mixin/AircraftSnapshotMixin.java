package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.sbwnpc.squad.integration.sbw.SbwAircraftSnapshots;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VehicleEntity.class, remap = false)
public abstract class AircraftSnapshotMixin {
    @Inject(method = "buildBvrSyncNbt", at = @At("TAIL"))
    private void sbwnpc$writeAircraftVisuals(CompoundTag tag, CallbackInfo ci) {
        SbwAircraftSnapshots.write((VehicleEntity) (Object) this, tag);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void sbwnpc$readAircraftVisuals(CompoundTag tag, CallbackInfo ci) {
        SbwAircraftSnapshots.read((VehicleEntity) (Object) this, tag);
    }
}
