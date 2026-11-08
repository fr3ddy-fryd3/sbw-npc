package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.tactics.ReconRules
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.squad.Squad
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Whether water lies between a squad and its objective (owner 08.11: an attack with recon onto an
 * island stood on the bank; the recon stages and the wait for their report fell on the water and the
 * near bank). While it does, those points keep off and the attack's own posts take it across, as they
 * do without recon. Measured along the straight line, block by block at the surface: the long-route
 * planner's route stops at a shore and never says what lies beyond.
 *
 * Ends: a new order; no water left on the line; the squad within [ARRIVED] of its objective; or
 * [TIMEOUT_TICKS] since the water was first seen.
 */
internal class WaterCrossings(private val level: ServerLevel) {
    private class Watch(val stamp: Int) {
        var checkedAt = Long.MIN_VALUE / 2
        var crossing = false
        var seenAt: Long? = null
    }

    private val watches = HashMap<UUID, Watch>()

    /** Whether [squad], whose men on foot stand round [centre], has water to cross to its objective now. */
    fun crossing(squad: Squad, centre: Vec3, now: Long): Boolean {
        val objective = squad.objective?.let(Vec3::atBottomCenterOf) ?: return forget(squad.id)
        if (Math.hypot(objective.x - centre.x, objective.z - centre.z) <= ARRIVED) return forget(squad.id)
        val watch = watches[squad.id]?.takeIf { it.stamp == squad.orderStamp } ?: Watch(squad.orderStamp).also { watches[squad.id] = it }
        watch.seenAt?.let { if (now - it >= TIMEOUT_TICKS) return timeout(squad, watch) }
        if (now - watch.checkedAt >= CHECK_TICKS) {
            watch.checkedAt = now
            val crossing = ReconRules.waterBetween(line(centre, objective).map(::wet))
            if (crossing != watch.crossing) DebugFlags.log(LogGroup.ORDER, "[journey] squad={} crossing={}", squad.id, crossing)
            watch.crossing = crossing
            if (crossing && watch.seenAt == null) watch.seenAt = now
        }
        return watch.crossing
    }

    // Every block's column from [from] to [to].
    private fun line(from: Vec3, to: Vec3): List<BlockPos> {
        val steps = maxOf(1, Math.ceil(Math.hypot(to.x - from.x, to.z - from.z)).toInt())
        return (0..steps).map { BlockPos.containing(from.lerp(to, it.toDouble() / steps)) }
    }

    // Water at the top of the column, in a loaded chunk only: an unloaded one says nothing.
    private fun wet(column: BlockPos): Boolean {
        if (!level.hasChunkAt(column)) return false
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.x, column.z)
        return level.getFluidState(BlockPos(column.x, top - 1, column.z)).`is`(FluidTags.WATER)
    }

    private fun timeout(squad: Squad, watch: Watch): Boolean {
        if (watch.crossing) DebugFlags.log(LogGroup.ORDER, "[journey] squad={} crossing=false reason=crossing_timeout", squad.id)
        watch.crossing = false
        return false
    }

    private fun forget(squad: UUID): Boolean {
        watches.remove(squad)
        return false
    }

    fun forgetSquad(squad: UUID) {
        watches.remove(squad)
    }

    fun clear() = watches.clear()

    companion object {
        /** Within this of the objective it is there: the crossing no longer keeps it travelling. */
        const val ARRIVED = 24.0

        /** However the crossing goes, after this long (4 min) the squad deploys where it is. */
        const val TIMEOUT_TICKS = 4800L

        /** Between looks along the line for one squad: 1 s. */
        private const val CHECK_TICKS = 20L
    }
}
