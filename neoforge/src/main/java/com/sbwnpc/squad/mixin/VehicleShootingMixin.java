package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.sbwnpc.squad.combat.DetectionSightline;
import com.sbwnpc.squad.combat.Sightline;
import com.sbwnpc.squad.entity.NpcEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SBW auto-fire reads Mob.target; detecting someone through a window must not permit a shot. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleShootingMixin {
    @Inject(
        method = "canShoot(Lnet/minecraft/world/entity/LivingEntity;)Z",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void sbwnpc$checkNpcFiringLane(LivingEntity shooter, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(shooter instanceof NpcEntity npc)) return;
        LivingEntity target = npc.getTarget();
        if (target == null) return;
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (!(vehicle.level() instanceof ServerLevel level)) return;
        Entity aimed = target.getVehicle() != null ? target.getVehicle() : target;
        if (!DetectionSightline.INSTANCE.canSee(npc, target) ||
            Sightline.INSTANCE.blocked(level, vehicle.getShootPos(shooter, 1f), aimed.getBoundingBox().getCenter(), vehicle)) {
            cir.setReturnValue(false);
        }
    }
}
