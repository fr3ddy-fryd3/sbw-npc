package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.experience.ExperienceAwards;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Direct setHealth healing (including SBW kits) consumes eligible combat wounds too. */
@Mixin(LivingEntity.class)
public abstract class ExperienceHealingMixin {
    @Unique private float sbwnpc$healthBefore;

    @Inject(method = "setHealth", at = @At("HEAD"))
    private void sbwnpc$before(float health, CallbackInfo ci) {
        sbwnpc$healthBefore = ((LivingEntity)(Object)this).getHealth();
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void sbwnpc$after(float health, CallbackInfo ci) {
        LivingEntity patient = (LivingEntity)(Object)this;
        if (!patient.level().isClientSide()) {
            ExperienceAwards.INSTANCE.healed(patient, patient.getHealth() - sbwnpc$healthBefore);
        }
    }
}
