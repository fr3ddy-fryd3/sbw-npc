package com.sbwnpc.squad.combat

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import kotlin.math.sqrt

/**
 * How much wider a shot at a target in the dark scatters (#155), by the band of light at the
 * target, see [SpottingTime.Light.hitFactor].
 *
 * Against a target smaller than the spread, hits go with the square of the spread, so a hit factor
 * f is a spread factor of 1/√f: 0.6 of the hits in the dark is 1.29 times the spread. The light is
 * read as spotting reads it. A target that fired in the last [FLASH_TICKS] is lit by his own muzzle
 * flash and counts as in full light, unless his gun is suppressed.
 */
object DarkAim {
    private const val FLASH_TICKS = 40L
    private const val MAX_LIGHT = 15

    /** The spread factor for a shot at [target]; 1 for none. */
    fun spreadFactor(level: Level, target: Entity?): Double {
        if (target == null || Hearing.flashedWithin(target, level.gameTime, FLASH_TICKS)) return 1.0
        return spreadFactor(level, target.blockPosition())
    }

    /** The spread factor for a shot at the point [pos], such as a mortar's aim. */
    fun spreadFactor(level: Level, pos: BlockPos): Double = forLight(level.getMaxLocalRawBrightness(pos))

    internal fun forLight(light: Int): Double = 1.0 / sqrt(SpottingTime.Light.of(light.coerceIn(0, MAX_LIGHT)).hitFactor)
}
