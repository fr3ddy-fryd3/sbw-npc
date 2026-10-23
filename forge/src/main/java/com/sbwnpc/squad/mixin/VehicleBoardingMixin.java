package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.sbwnpc.squad.integration.sbw.AlliedVehicleBoarding;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve subclass interactions; override only the base vehicle's NPC-driver replacement. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleBoardingMixin {
    @Inject(
        method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void sbwnpc$boardAlongsideAlliedDriver(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = AlliedVehicleBoarding.interact((VehicleEntity) (Object) this, player, hand);
        if (result != null) cir.setReturnValue(result);
    }
}
