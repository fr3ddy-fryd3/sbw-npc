package com.sbwnpc.squad.mixin;

import net.minecraft.gametest.framework.StructureGridSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * On this mod's GameTest server, scenes of one batch stand {@link #GAP} blocks apart instead of 5
 * (columns) and 6 (rows). Scenes of a batch run at once, and 5 blocks let a squad see, report and
 * shoot at the enemy of the scene next door: a MOVE squad stood still for a target 65 blocks off in
 * its neighbour's arena. Outside a run started with {@code sbwnpc.gameTestReports}, such as the
 * vanilla {@code /test} command in a game, the vanilla spacing is kept.
 */
@Mixin(StructureGridSpawner.class)
public abstract class GameTestSpacingMixin {
    /** Farther than anything is seen (256 blocks), so no contact crosses between scenes. */
    private static final int GAP = 264;

    private static final boolean OUR_TEST_SERVER = System.getProperty("sbwnpc.gameTestReports") != null;

    @ModifyConstant(method = "spawnStructure", constant = @Constant(intValue = 5))
    private int sbwnpc$columnGap(int vanilla) {
        return OUR_TEST_SERVER ? GAP : vanilla;
    }

    @ModifyConstant(method = "spawnStructure", constant = @Constant(intValue = 6))
    private int sbwnpc$rowGap(int vanilla) {
        return OUR_TEST_SERVER ? GAP : vanilla;
    }
}
