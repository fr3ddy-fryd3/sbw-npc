package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.sbwnpc.squad.domain.port.Ports;
import com.sbwnpc.squad.experience.ExperienceAwards;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayDeque;

/** SBW vehicles are plain Entities and do not emit LivingDamageEvent. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleExperienceMixin {
    @Unique private final ArrayDeque<Float> sbwnpc$health = new ArrayDeque<>();
    @Inject(method = "m_6469_", at = @At("HEAD"))
    private void sbwnpc$before(DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        sbwnpc$health.push(Ports.INSTANCE.getVehicles().healthFraction((Entity)(Object)this));
    }
    @Inject(method = "m_6469_", at = @At("RETURN"))
    private void sbwnpc$after(DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        ExperienceAwards.vehicleDamage((Entity)(Object)this, source, sbwnpc$health.pop());
    }
}
