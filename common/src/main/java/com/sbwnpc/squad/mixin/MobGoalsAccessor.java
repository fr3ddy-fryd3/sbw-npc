package com.sbwnpc.squad.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads a mob's target goals, to tell whether it hunts players (#135). */
@Mixin(Mob.class)
public interface MobGoalsAccessor {
    @Accessor("targetSelector")
    GoalSelector sbwnpcTargetSelector();
}
