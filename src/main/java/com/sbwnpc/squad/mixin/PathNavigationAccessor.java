package com.sbwnpc.squad.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Allows our navigator to preserve active-route state during speculative native searches. */
@Mixin(PathNavigation.class)
public interface PathNavigationAccessor {
    @Accessor("targetPos")
    BlockPos sbwnpcGetTarget();

    @Accessor("targetPos")
    void sbwnpcSetTarget(BlockPos target);

    @Accessor("reachRange")
    int sbwnpcGetReachRange();

    @Accessor("reachRange")
    void sbwnpcSetReachRange(int range);

    @Accessor("isStuck")
    boolean sbwnpcIsStuck();

    @Accessor("isStuck")
    void sbwnpcSetStuck(boolean stuck);

    @Invoker("resetStuckTimeout")
    void sbwnpcResetStuckTimeout();
}
