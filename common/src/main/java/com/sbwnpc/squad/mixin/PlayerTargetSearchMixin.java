package com.sbwnpc.squad.mixin;

import com.sbwnpc.squad.combat.HostileMobs;
import com.sbwnpc.squad.combat.PlayerHuntingGoal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mob that hunts players hunts squad NPCs the same way (#135).
 *
 * Vanilla's player search asks {@code level.getNearestPlayer}, which never returns an NPC. After it,
 * the nearest NPC passing the very same {@link TargetingConditions} (range, sight, the goal's own
 * selector such as a wolf's or golem's anger) is taken if it is nearer. Subclasses keep their own
 * gate around this search: a spider's daylight check runs before it. Goals that search players
 * their own way (endermen, phantoms) and Brain-driven mobs (piglins, hoglins) are not reached.
 */
@Mixin(NearestAttackableTargetGoal.class)
public abstract class PlayerTargetSearchMixin<T extends LivingEntity> extends TargetGoal implements PlayerHuntingGoal {
    @Shadow @Final protected Class<T> targetType;
    @Shadow protected LivingEntity target;
    @Shadow protected TargetingConditions targetConditions;

    @Shadow protected abstract AABB getTargetSearchArea(double targetDistance);

    private PlayerTargetSearchMixin(Mob mob, boolean mustSee) {
        super(mob, mustSee);
    }

    @Override
    public boolean sbwnpcHuntsPlayers() {
        return this.targetType == Player.class || this.targetType == ServerPlayer.class;
    }

    @Inject(method = "findTarget", at = @At("TAIL"))
    private void sbwnpc$squadNpcsToo(CallbackInfo ci) {
        if (!sbwnpcHuntsPlayers()) return;
        LivingEntity npc = HostileMobs.INSTANCE.nearestNpc(this.mob, this.targetConditions, this.getTargetSearchArea(this.getFollowDistance()));
        if (npc != null && (this.target == null || this.mob.distanceToSqr(npc) < this.mob.distanceToSqr(this.target))) this.target = npc;
    }
}
