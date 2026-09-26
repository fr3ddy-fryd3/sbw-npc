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
 * Earlier each member flipped between running and firing on its own timer, so the "covering" half
 * was covering nothing: the runners were long gone, strung out across the field.
 */
object Withdrawal {
    /** How far one half runs before stopping to cover the other. */
    private const val BOUND_DISTANCE = 12.0
    /** Close enough to a bound position to count as having made it. */
    private const val ARRIVED = 2.5
    /** A bound that takes longer than this has stalled — someone is stuck; swap anyway. */
    private const val MAX_BOUND_TICKS = 160L

    private class State(val point: Vec3) {
        /** Which half (slot parity) is running. */
        var moving = 0
        var boundStart = 0L
        /** Where the running half is headed this bound. */
        var anchor: Vec3? = null
        var updatedTick = -1L
        /** Anyone in the squad has an enemy to shoot at. Without contact there is nothing to
         *  cover against, and everyone simply runs. */
        var inContact = false
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
            return SquadFormation.slotTarget(member, point, point.subtract(member.position()), false, SquadFormation.COMBAT_SPACING)
        }
        val index = member.slotIndex(squad).coerceAtLeast(0)
        if (index % 2 != state.moving) return null
        val anchor = state.anchor ?: return null
        return SquadFormation.slotTarget(member, anchor, point.subtract(anchor), false, SquadFormation.COMBAT_SPACING)
    }

    private fun update(level: ServerLevel, squad: Squad, state: State) {
        val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
        if (members.isEmpty()) return
        state.inContact = members.any { it.target?.isAlive == true }
        if (!state.inContact) {
            state.anchor = null
            return
        }
        val runners = members.filter { it.slotIndex(squad).coerceAtLeast(0) % 2 == state.moving }
        val anchor = state.anchor
        val boundDone = anchor != null && runners.all { m ->
            m.position().distanceTo(
                SquadFormation.slotTarget(m, anchor, state.point.subtract(anchor), false, SquadFormation.COMBAT_SPACING)
            ) <= ARRIVED || m.position().distanceTo(state.point) <= SquadFormation.ARRIVAL_RADIUS
        }
        val stalled = level.gameTime - state.boundStart > MAX_BOUND_TICKS
        if (anchor == null || boundDone || stalled) {
            if (anchor != null) state.moving = 1 - state.moving
            state.boundStart = level.gameTime
            // The next bound starts from where the other half — now the runners — actually is.
            val next = members.filter { it.slotIndex(squad).coerceAtLeast(0) % 2 == state.moving }
                .ifEmpty { members }
            val from = Vec3(next.sumOf { it.x } / next.size, next.sumOf { it.y } / next.size, next.sumOf { it.z } / next.size)
            val toPoint = state.point.subtract(from)
            val length = toPoint.horizontalDistance()
            // Leapfrog: past the covering half, but never more than one bound.
            state.anchor = if (length <= BOUND_DISTANCE) state.point else from.add(toPoint.scale(BOUND_DISTANCE / length))
        }
    }
}
