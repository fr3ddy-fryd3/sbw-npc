package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.sbwnpc.squad.combat.NearMisses;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A bullet's block hit raises no Forge event in SBW; NPCs next to it still notice the round.
 *
 * SBW changed the hook between patches: 0.8.9 only has the final {@code onHitBlock(Vec3, BlockHitResult)},
 * 0.8.9.2 only overrides vanilla's {@code m_8060_(BlockHitResult)}. Each jar has exactly one of the two,
 * so both injectors are optional ({@code require = 0}) and the other never matches.
 */
@Mixin(value = ProjectileEntity.class, remap = false)
public abstract class BulletImpactMixin {
    @Inject(method = "m_8060_(Lnet/minecraft/world/phys/BlockHitResult;)V", at = @At("HEAD"), remap = false, require = 0)
    private void sbwnpc$noticeNearMiss(BlockHitResult result, CallbackInfo ci) {
        NearMisses.INSTANCE.landed((ProjectileEntity) (Object) this, result.getLocation());
    }

    @Inject(
        method = "onHitBlock(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/BlockHitResult;)V",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void sbwnpc$noticeNearMissLegacy(Vec3 location, BlockHitResult result, CallbackInfo ci) {
        NearMisses.INSTANCE.landed((ProjectileEntity) (Object) this, result.getLocation());
    }
}
