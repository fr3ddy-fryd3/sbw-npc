package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.client.ToolInput;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric has no input event for a use-item click, which NeoForge lets the tool intercept before it
 * becomes {@code use}. A Ctrl-click with the squad tool is a mode switch, not a use.
 */
@Mixin(Minecraft.class)
public abstract class UseItemMixin {

    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void sbwnpc$toolModeSwitch(CallbackInfo ci) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (ToolInput.INSTANCE.toggleModeOnControlClick(hand)) {
                ci.cancel();
                return;
            }
        }
    }
}
