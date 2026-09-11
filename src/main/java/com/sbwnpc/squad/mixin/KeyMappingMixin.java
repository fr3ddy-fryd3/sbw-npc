package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.client.HudOverlayState;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla hard-binds physical keys 1-9 to hotbar slot selection. The quick-command HUD
 * deliberately reuses those same physical keys for its own squad/order picker while it's open
 * (see HudKeys) — bound as separate KeyMapping instances, so consumeClick() still fires on both.
 * This only needs to suppress the vanilla hotbar-slot mappings specifically (by reference, not by
 * key code — our own slot mappings share the key code on purpose and must keep working), the same
 * technique SuperbWarfare's own KeymappingMixin uses to steal 1-9 for vehicle seat selection.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {

    @Inject(method = "consumeClick()Z", at = @At("HEAD"), cancellable = true)
    private void sbwnpc$blockHotbarWhileHudOpen(CallbackInfoReturnable<Boolean> cir) {
        if (!HudOverlayState.isOpen()) return;

        KeyMapping self = (KeyMapping) (Object) this;
        for (KeyMapping hotbarKey : Minecraft.getInstance().options.keyHotbarSlots) {
            if (hotbarKey == self) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
