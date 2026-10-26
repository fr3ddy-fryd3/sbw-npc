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

    /** [firedAt] is the game tick of the man's last shot; [job] is his current task, null when he has none. */
    fun sample(tick: Long, id: UUID, alive: Boolean, x: Double, z: Double, job: String?, firedAt: Long) {
        val track = tracks.getOrPut(id) { Track(x, z, tick, job, tick, null) }
        track.alive = alive
        if (firedAt > track.lastFiredAt) track.lastFiredAt = firedAt
        if (Math.hypot(x - track.x, z - track.z) >= MOVED_BLOCKS) {
            track.x = x
            track.z = z
            track.movedAt = tick
        }
        if (job != track.job) {
            if (track.job != null && job != null) {
                track.jobChanges++
                if (job == track.previousJob && tick - track.jobSince <= REVERSAL_TICKS) track.quickReversals++
            }
            track.previousJob = track.job
            track.job = job
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
