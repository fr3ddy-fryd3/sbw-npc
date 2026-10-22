package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome

/**
 * Whether an NPC has noticed something it has a clear line to (#114): the one rule every own-eyes
 * scan goes through, so the sensor, the drone operator, the mortar and anti-drone fire cannot drift
 * apart on what "seen" means.
 *
 * One per scan, each with its own [SpottingProgress]: the scans look at different ranges on
 * different intervals, and one scan's [endScan] would drain what another was filling. [by] names
 * the scan in the [group] log; [size] is what it looks for.
 */
class Noticing(
    private val by: String,
    private val group: LogGroup = LogGroup.SPOTTING,
    private val size: SpottingTime.Size = SpottingTime.Size.PERSON,
    /** False for a mounted gunner: his optics have no cut-off, only the time, see [SpottingTime.seconds]. */
    private val cutOff: Boolean = true,
) {
    private val progress = SpottingProgress()

    /**
     * Whether [observer] has noticed [candidate]. Instant for its current target, for an enemy that
     * has just fired, and while [observer] is under fire; otherwise it takes [SpottingTime.seconds]
     * by distance, light and weather at [candidate], [offAxis], posture, [grassBlocks], size and
     * the observer's experience.
     */
    fun noticed(observer: NpcEntity, level: ServerLevel, candidate: Entity, offAxis: Double, grassBlocks: Int = 0): Boolean {
        val now = level.gameTime
        val gaveAway = candidate is NpcEntity && candidate.firedRecently(GIVEAWAY_TICKS)
        if (candidate === observer.target || gaveAway || observer.incomingFire.pending(now)) {
            progress.fill(candidate.uuid, now)
            return true
        }
        val target = SpottingTime.Target(
            observer.distanceTo(candidate).toDouble(),
            level.getMaxLocalRawBrightness(candidate.blockPosition()),
            offAxis,
            candidate.isCrouching,
            candidate.isSprinting,
            grassBlocks,
            weatherAt(level, candidate.blockPosition()),
            size,
        )
        val required = SpottingTime.seconds(SpottingTime.Observer.of(observer.npcRank, observer.npcClass), target, cutOff)
        val before = progress.seconds(candidate.uuid)
        return progress.advance(candidate.uuid, now, required).also { seen ->
            // Once each: when he is first noticed, and when he first comes into view too far off for this light.
            if (seen && required != null && before < required) {
                DebugFlags.log(group, "{} noticed {} by {} at {} blocks, light {}, {}, after {}s of {}s needed", observer.uuid, candidate.uuid, by, target.distance.toInt(), target.light, target.weather, "%.1f".format(progress.seconds(candidate.uuid)), "%.1f".format(required))
            } else if (required == null && before == 0.0) {
                DebugFlags.log(group, "{} cannot notice {} by {} at {} blocks, light {}, {}: past the cut-off", observer.uuid, candidate.uuid, by, target.distance.toInt(), target.light, target.weather)
            }
        }
    }

    /** After a scan: whatever it did not look at drains, see [SpottingProgress.endScan]. */
    fun endScan(now: Long) = progress.endScan(now)

    companion object {
        /** An enemy that fired this recently has given his position away. */
        private const val GIVEAWAY_TICKS = 40

        /**
         * The weather where [pos] is. Not [Level.isRainingAt]: that needs open sky and rain, so it says
         * "clear" for a man under a roof or a tree and for every snowy biome.
         */
        fun weatherAt(level: Level, pos: BlockPos): SpottingTime.Weather {
            if (!level.isRaining) return SpottingTime.Weather.CLEAR
            return when (level.getBiome(pos).value().getPrecipitationAt(pos)) {
                Biome.Precipitation.NONE -> SpottingTime.Weather.CLEAR
                Biome.Precipitation.SNOW -> SpottingTime.Weather.SNOW
                Biome.Precipitation.RAIN -> if (level.isThundering) SpottingTime.Weather.STORM else SpottingTime.Weather.RAIN
            }
        }
    }
}
