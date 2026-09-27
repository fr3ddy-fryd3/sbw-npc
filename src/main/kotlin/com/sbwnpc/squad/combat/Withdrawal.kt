package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A squad's fighting withdrawal, run for the squad as a whole rather than by each member alone.
 *
 * Bounding overwatch: the squad is two halves by slot. One half makes a short bound toward the
 * rally point while the other holds and fires; only when the movers have reached their new
 * positions (or a bound has plainly stalled) do the halves swap. Each bound is short, so the half
 * that is running is never far from the half covering it — the whole point of the covering fire.
 *
 * Positions are fixed when a bound starts and laid out along the line of retreat itself: a line
 * across it, every man straight back from the enemy. Borrowing the squad formation for this went
 * wrong both ways — its facing follows the lead slot, so with the lead in the other half the line
 * could turn along the axis and put men metres toward the enemy, and recomputing it every tick from
 * a moving lead had them chasing a point that kept shifting.
 */
object Withdrawal {
    /** How far one half runs before stopping to cover the other. */
    private const val BOUND_DISTANCE = 12.0
    /** Gap between men in a bound's line. */
    private const val LATERAL_SPACING = 4.0
    /** Close enough to a bound position to count as having made it. */
    private const val ARRIVED = 3.0
    /** A path that ended this near counts too: the exact spot may not be standable. */
    private const val SETTLED = 6.0
    /** A bound that takes longer than this has stalled — someone is stuck; swap anyway. */
    private const val MAX_BOUND_TICKS = 100L
    private const val STATUS_LOG_TICKS = 40L

    private class State(val point: Vec3) {
        /** Which half (slot parity) is running. */
        var moving = 0
        var boundStart = 0L
        /** Each runner's position for the current bound. Empty between bounds. */
        val targets = HashMap<UUID, Vec3>()
        var updatedTick = -1L
        /** Anyone in the squad has an enemy to shoot at. Without contact there is nothing to
         *  cover against, and everyone simply runs. */
        var inContact = false
        var members: List<NpcEntity> = emptyList()
        var lastStatusTick = Long.MIN_VALUE / 2
    }

    private val bySquad = HashMap<UUID, State>()

    fun clearAll() = bySquad.clear()

    /**
     * Where [member] should be right now while its squad falls back to [point]: a position to run
     * to, or null to hold where it is and fire.
     */
    fun positionFor(member: NpcEntity, squad: Squad, point: Vec3): Vec3? {
        val level = member.level() as? ServerLevel ?: return point
        val state = bySquad[squad.id]?.takeIf { it.point == point } ?: State(point).also { bySquad[squad.id] = it }
        if (state.updatedTick != level.gameTime) {
            state.updatedTick = level.gameTime
            update(level, squad, state)
        }
        if (!state.inContact) {
            // Nothing to cover against: everyone to the point, spread across the line of retreat.
            val order = state.members.indexOf(member).coerceAtLeast(0)
            return lineSpot(point, point.subtract(member.position()), order, state.members.size)
        }
        return state.targets[member.uuid]
    }

    private fun half(member: NpcEntity, squad: Squad) = member.slotIndex(squad).coerceAtLeast(0) % 2

    private fun update(level: ServerLevel, squad: Squad, state: State) {
        val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
        state.members = members
        if (members.isEmpty()) return
        // There: hold it. SquadOrderBehaviour makes the same switch, but only for members with
        // nobody to shoot at — under fire it never ran, and the squad stayed "retreating" forever.
        // Three in four is "there": one man pinned in a ditch shouldn't keep the rest running.
        val there = members.count { SquadFormation.reachedPoint(it, state.point, squad.members.size) }
        if (there * 4 >= members.size * 3) {
            squad.order = com.sbwnpc.squad.squad.SquadOrder.DEFEND
            bySquad.remove(squad.id)
            DebugFlags.log("[retreat-debug] squad {} reached its rally point, defending", squad.name)
            return
        }
        state.inContact = members.any { it.target?.isAlive == true }
        if (DebugFlags.LOGGING_ENABLED && level.gameTime - state.lastStatusTick >= STATUS_LOG_TICKS) {
            state.lastStatusTick = level.gameTime
            // Where each man is and what he is doing: a squad "stuck" on the way back reads as one
            // of these not changing between lines.
            DebugFlags.log(
                "[retreat-debug] squad {} status: contact={} moving half {} bound {} ticks old: {}",
                squad.name, state.inContact, state.moving, level.gameTime - state.boundStart,
                members.joinToString("; ") { m ->
                    val spot = state.targets[m.uuid]
                    "${m.uuid.toString().take(8)} half ${half(m, squad)} at ${m.blockPosition().toShortString()} " +
                        "point ${m.position().distanceTo(state.point).toInt()} " +
                        (spot?.let { "spot ${m.position().distanceTo(it).toInt()} " } ?: "no spot ") +
                        "target=${m.target != null} navDone=${m.navigation.isDone} pinned=${m.combatLockedByCover()} dug=${m.diggedIn}"
                }
            )
        }
        if (!state.inContact) {
            state.targets.clear()
            return
        }
        val runners = members.filter { half(it, squad) == state.moving }
        val boundDone = state.targets.isNotEmpty() && runners.all { m ->
            // Pinned down in cover or dug in: it isn't coming this bound, so don't wait on it.
            if (m.combatLockedByCover() || m.diggedIn) return@all true
            val spot = state.targets[m.uuid] ?: return@all true
            val d = m.position().distanceTo(spot)
            d <= ARRIVED || (m.navigation.isDone && d <= SETTLED) ||
                m.position().distanceTo(state.point) <= SquadFormation.ARRIVAL_RADIUS
        }
        val stalled = level.gameTime - state.boundStart > MAX_BOUND_TICKS
        if (state.targets.isEmpty() || boundDone || stalled) {
            if (state.targets.isNotEmpty()) state.moving = 1 - state.moving
            startBound(level, squad, state, members)
        }
    }

    private fun startBound(level: ServerLevel, squad: Squad, state: State, members: List<NpcEntity>) {
        state.boundStart = level.gameTime
        state.targets.clear()
        val unsorted = members.filter { half(it, squad) == state.moving }.ifEmpty { members }
        val from = Vec3(
            unsorted.sumOf { it.x } / unsorted.size, unsorted.sumOf { it.y } / unsorted.size, unsorted.sumOf { it.z } / unsorted.size
        )
        val toPoint = state.point.subtract(from)
        // Places are handed out left to right in the order the men already stand across the line
        // of retreat. By slot number instead, a man on the left end could be sent to the right
        // end — thirty blocks sideways, across everyone else's path.
        val flat = Vec3(toPoint.x, 0.0, toPoint.z).let { if (it.lengthSqr() < 1.0e-4) Vec3(0.0, 0.0, 1.0) else it.normalize() }
        val right = Vec3(-flat.z, 0.0, flat.x)
        val runners = unsorted.sortedBy { it.position().subtract(from).dot(right) }
        val length = toPoint.horizontalDistance()
        val anchor = if (length <= BOUND_DISTANCE) state.point else from.add(toPoint.scale(BOUND_DISTANCE / length))
        runners.forEachIndexed { i, m -> state.targets[m.uuid] = lineSpot(anchor, toPoint, i, runners.size) }
        DebugFlags.log(
            "[retreat-debug] squad {} half {} bounds {} blocks toward {} ({} men)",
            squad.name, state.moving, "%.1f".format(minOf(length, BOUND_DISTANCE)), state.point, runners.size
        )
    }

    /** The [i]-th of [n] spots in a line across [direction], centred on [anchor]. */
    private fun lineSpot(anchor: Vec3, direction: Vec3, i: Int, n: Int): Vec3 {
        val flat = Vec3(direction.x, 0.0, direction.z)
        if (flat.lengthSqr() < 1.0e-4 || n <= 1) return anchor
        val dir = flat.normalize()
        val right = Vec3(-dir.z, 0.0, dir.x)
        return anchor.add(right.scale((i - (n - 1) / 2.0) * LATERAL_SPACING))
    }
}
