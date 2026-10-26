package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.npc.NpcClass
import java.util.UUID

/**
 * What a mixed-role load scene reads besides tick time (#192): men who stand still without firing,
 * and men whose job flips back and forth. Fed one sample per man per [SAMPLE_TICKS]; knows nothing
 * of the world, so a scene can feed it from anywhere.
 */
class RoleLoadMetrics(private val roles: Map<UUID, NpcClass>) {
    private class Track(var x: Double, var z: Double, var movedAt: Long, var job: String?, var jobSince: Long, var previousJob: String?) {
        var jobChanges = 0
        var quickReversals = 0
        var lastFiredAt = Long.MIN_VALUE
        var alive = true
    }

    private val tracks = HashMap<UUID, Track>()

    /** One man as seen at a sample: [firedAt] is the game tick of his last shot, [job] his current task, null when he has none. */
    data class Reading(val id: UUID, val alive: Boolean, val x: Double, val z: Double, val job: String?, val firedAt: Long)

    fun sample(tick: Long, reading: Reading) {
        val track = tracks.getOrPut(reading.id) { Track(reading.x, reading.z, tick, reading.job, tick, null) }
        track.alive = reading.alive
        if (reading.firedAt > track.lastFiredAt) track.lastFiredAt = reading.firedAt
        if (Math.hypot(reading.x - track.x, reading.z - track.z) >= MOVED_BLOCKS) {
            track.x = reading.x
            track.z = reading.z
            track.movedAt = tick
        }
        if (reading.job != track.job) {
            if (track.job != null && reading.job != null) {
                track.jobChanges++
                if (reading.job == track.previousJob && tick - track.jobSince <= REVERSAL_TICKS) track.quickReversals++
            }
            track.previousJob = track.job
            track.job = reading.job
            track.jobSince = tick
        }
    }

    /** Living men who have neither moved [MOVED_BLOCKS] nor fired for at least [IDLE_TICKS] at [tick]. */
    fun idle(tick: Long): List<UUID> = tracks.filter { (_, t) -> t.alive && tick - t.movedAt >= IDLE_TICKS && tick - t.lastFiredAt >= IDLE_TICKS }.keys.toList()

    /** Job changes per man-minute and quick A-B-A reversals, over all men and per role. */
    fun report(tick: Long): String {
        val minutes = (tick / 1200.0).coerceAtLeast(1.0 / 60)
        fun line(label: String, ids: Collection<UUID>): String {
            val rows = ids.mapNotNull { tracks[it] }
            val idle = ids.count { it in idleIds }
            return "$label men=${rows.size} alive=${rows.count { it.alive }} idle=$idle " +
                "jobChangesPerManMinute=${"%.2f".format(rows.sumOf { it.jobChanges } / minutes / rows.size.coerceAtLeast(1))} " +
                "quickReversals=${rows.sumOf { it.quickReversals }}"
        }
        idleIds = idle(tick).toSet()
        return buildString {
            appendLine(line("all", tracks.keys))
            roles.entries.groupBy({ it.value }, { it.key }).toSortedMap().forEach { (role, ids) -> appendLine(line(role.name, ids)) }
        }
    }

    private var idleIds: Set<UUID> = emptySet()

    companion object {
        const val SAMPLE_TICKS = 20L
        const val MOVED_BLOCKS = 1.0
        const val IDLE_TICKS = 400L
        const val REVERSAL_TICKS = 200L
    }
}
