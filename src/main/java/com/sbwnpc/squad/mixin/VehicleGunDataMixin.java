package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.sbwnpc.squad.integration.sbw.VehicleDroneAccuracy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Covers every vehicleShoot overload, including the delayed autonomous-turret path. */
@Mixin(value = GunData.class, remap = false)
public abstract class VehicleGunDataMixin {
    @ModifyVariable(
        method = "shoot(Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0,
        remap = false
    )
    private ShootParameters sbwnpc$applyDroneSpread(ShootParameters parameters) {
        return VehicleDroneAccuracy.adjustShot(parameters);
    }
}
