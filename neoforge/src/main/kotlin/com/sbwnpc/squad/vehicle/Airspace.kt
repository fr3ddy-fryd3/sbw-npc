package com.sbwnpc.squad.vehicle

import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Keeps helicopters out of each other's rotor disc.
 *
 * Two aircraft given the same job compute the same answer — the standoff ring around a target and
 * the loiter circle around an objective are both derived purely from the target and the airframe's
 * own position, so a pair sent to the same fight converges on the same point in the sky and holds
 * there, overlapping. Reported in-game as "вертолеты близко друг к другу летают".
 *
 * Two independent mechanisms, because either one alone leaves a gap:
 *  - [layerFor] stacks them vertically by a stable ordering, which costs nothing and solves the
 *    common case (same objective, same ring) before it ever becomes a conversion.
 *  - [separate] pushes the station away from whoever is too close right now, which is what handles
 *    the cases layering can't: crossing tracks, a landing pad, an aircraft that arrived from the
 *    other side.
 *
 * Pure maths on purpose (see `HelicopterFlightController` for the same reasoning) — it is unit
 * tested rather than flown to find out.
 */
object Airspace {
    /** Horizontal distance two helicopters are expected to keep. */
    const val SEPARATION = 26.0
    /** Vertical spacing between stacked aircraft. */
    const val LAYER_HEIGHT = 6.0
    /** Beyond this many aircraft in one place, stacking higher stops being useful. */
    const val MAX_LAYERS = 3
    /** Only aircraft this close are worth stacking against. Layering is for two machines
     *  converging on the same piece of sky, not for everything inside [AWARENESS_RANGE] — counting
     *  the wider set is what had a lone gunship cruising three bands higher than its mission
     *  called for. */
    const val LAYER_RANGE = 48.0
    /** How far out another helicopter is worth knowing about. */
    const val AWARENESS_RANGE = 96.0

    /**
     * [station] moved away from any of [neighbours] standing inside [SEPARATION] of [self].
     *
     * The push shrinks as the gap opens, so a pair holding the same station settles at roughly
     * [SEPARATION] apart instead of oscillating: each one's station sits on the far side of the
     * other, and the correction goes to zero exactly where they should be.
     */
    fun separate(self: Vec3, station: Vec3, neighbours: List<Vec3>): Vec3 {
        var push = Vec3.ZERO
        for (other in neighbours) {
            val away = Vec3(self.x - other.x, 0.0, self.z - other.z)
            val gap = away.length()
            if (gap >= SEPARATION) continue
            // Exactly co-located has no "away" direction to use; any fixed one will do, and the
            // two aircraft pick opposite ones as soon as they have moved at all.
            val dir = if (gap < 1.0e-3) Vec3(1.0, 0.0, 0.0) else away.scale(1.0 / gap)
            push = push.add(dir.scale(SEPARATION - gap))
        }
        return if (push.lengthSqr() < 1.0e-6) station else station.add(push)
    }

    /**
     * Which altitude band this aircraft flies in, as extra clearance over its mission's own.
     *
     * Ordering is by UUID rather than by distance or spawn time so every aircraft in a group
     * computes the same answer for itself without talking to the others, and keeps that answer for
     * as long as the group is together — a band that changed as they manoeuvred would have them
     * swapping heights through each other.
     *
     * [neighbours] must already be filtered to aircraft that are actually flying and actually
     * close (see [LAYER_RANGE]). Parked machines belong in [separate]'s list, where they are
     * something to avoid hovering over, but not in this one: an aircraft does not need to climb
     * over something sitting on the ground.
     */
    fun layerFor(self: UUID, neighbours: List<UUID>): Int =
        neighbours.count { it < self }.coerceAtMost(MAX_LAYERS - 1)

    fun clearanceFor(self: UUID, neighbours: List<UUID>): Double = layerFor(self, neighbours) * LAYER_HEIGHT
}
