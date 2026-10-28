package com.sbwnpc.squad.mixin;

import com.atsuishio.superbwarfare.resource.model.VehicleModelReloadListener;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.sbwnpc.squad.integration.sbw.client.AircraftLods;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

@Mixin(value = VehicleModelReloadListener.class, remap = false)
public abstract class AircraftModelReloadMixin {
    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("TAIL"))
    private void sbwnpc$bakeAircraftLods(Map<ResourceLocation, BedrockModelPOJO> models,
            ResourceManager resources, ProfilerFiller profiler, CallbackInfo ci) {
        AircraftLods.reload(models, resources);
    }
}
