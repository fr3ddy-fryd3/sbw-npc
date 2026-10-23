package com.sbwnpc.squad.mixin;

import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.20.1 keeps an explosion's radius private; hearing an explosion needs it. */
@Mixin(Explosion.class)
public interface ExplosionAccessor {
    @Accessor("radius")
    float sbwnpcRadius();
}
