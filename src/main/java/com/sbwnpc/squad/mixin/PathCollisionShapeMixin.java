package com.sbwnpc.squad.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.sbwnpc.squad.entity.ai.PathBlockCache;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Empty walking nodes never need a voxel representation of the NPC's body. */
@Mixin(BlockCollisions.class)
public abstract class PathCollisionShapeMixin {
    @Shadow @Final private AABB box;
    @Shadow @Final @Mutable private VoxelShape entityShape;
    @Unique private Operation<VoxelShape> sbwnpc$deferredBodyShape;

    @WrapOperation(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    )
    private VoxelShape sbwnpc$deferPathBody(AABB bounds, Operation<VoxelShape> original,
            @Local(argsOnly = true) CollisionGetter getter) {
        if (!(getter instanceof PathBlockCache)) return original.call(bounds);
        sbwnpc$deferredBodyShape = original;
        return Shapes.empty();
    }

    @ModifyExpressionValue(
        method = "computeNext",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/BlockCollisions;entityShape:Lnet/minecraft/world/phys/shapes/VoxelShape;")
    )
    private VoxelShape sbwnpc$resolvePathBody(VoxelShape current) {
        Operation<VoxelShape> original = sbwnpc$deferredBodyShape;
        if (original == null) return current;
        sbwnpc$deferredBodyShape = null;
        // Retain other wrappers around Shapes.create as well as vanilla shape semantics.
        entityShape = original.call(box);
        return entityShape;
    }
}
