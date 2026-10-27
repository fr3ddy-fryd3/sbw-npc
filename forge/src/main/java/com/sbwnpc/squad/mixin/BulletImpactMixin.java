package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.sbwnpc.squad.combat.NearMisses;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A bullet's block hit raises no NeoForge event in SBW; NPCs next to it still notice the round. */
@Mixin(value = ProjectileEntity.class, remap = false)
public abstract class BulletImpactMixin {
    @Inject(method = "m_8060_(Lnet/minecraft/world/phys/BlockHitResult;)V", at = @At("HEAD"), remap = false)
    private void sbwnpc$noticeNearMiss(BlockHitResult result, CallbackInfo ci) {
        NearMisses.INSTANCE.landed((ProjectileEntity) (Object) this, result.getLocation());
    }
}
