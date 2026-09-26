package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.team.SquadTeams;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SuperbWarfare decides friend or foe through vanilla {@code isAlliedTo(Entity)} — its mines,
 * turrets, friendly-fire guard and perks all ask it. Vanilla only knows scoreboard teams, so allied
 * factions (and players, who are never put on a team) looked like strangers to it. Only ever says
 * yes: anything our factions don't cover is left to vanilla.
 */
@Mixin(Entity.class)
public abstract class EntityAllianceMixin {

    @Inject(method = "isAlliedTo(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void sbwnpc$factionsAndAlliances(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (other != null && SquadTeams.INSTANCE.sameSide((Entity) (Object) this, other)) cir.setReturnValue(true);
    }
}
