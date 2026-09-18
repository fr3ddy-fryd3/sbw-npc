package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity;
import com.sbwnpc.squad.combat.VehicleDroneAccuracy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AutoAimableEntity.class, remap = false)
public abstract class AutoAimableEntityMixin {
    @ModifyArg(
        method = "autoAim()V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/AutoAimableEntity;vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Lnet/minecraft/world/phys/Vec3;)V"
        ),
        index = 2,
        remap = false
    )
    private Vec3 sbwnpc$markAutonomousShot(Vec3 position) {
        return VehicleDroneAccuracy.markAutonomousShot((AutoAimableEntity) (Object) this, position);
    }

    @Inject(
        method = "rayShoot(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/Entity;Lcom/atsuishio/superbwarfare/data/gun/GunData;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void sbwnpc$applyDroneRayAccuracy(LivingEntity shooter, Entity target, GunData data, CallbackInfo ci) {
        if (VehicleDroneAccuracy.consumeMissedRay((AutoAimableEntity) (Object) this, shooter, target, data)) {
            ci.cancel();
        }
    }
}
