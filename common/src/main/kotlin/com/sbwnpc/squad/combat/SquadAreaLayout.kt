package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** [nudge]: a patrol's area moves only as far as it overlaps another, never a whole spacing step away. */
data class SquadAreaRequest(val squad: UUID, val faction: SquadFaction, val point: Vec3, val radius: Double, val nudge: Boolean = false)
data class SquadArea(val request: SquadAreaRequest, val center: Vec3)

/** Stable spatial leases. No combat roles, enemy knowledge or entity/world references. */
class SquadAreaLayout {
    private val areas = LinkedHashMap<UUID, SquadArea>()
    private val rejected = HashMap<UUID, MutableSet<Vec3>>()
    private var previous = emptyMap<UUID, SquadAreaRequest>()

    fun area(squad: UUID): SquadArea? = areas[squad]

    fun reconcile(requests: List<SquadAreaRequest>, ground: (SquadAreaRequest, Vec3) -> Vec3?) {
        val present = requests.associateBy { it.squad }
        for (request in requests) {
            val old = previous[request.squad]
            if (old?.point != request.point || old.faction != request.faction) rejected.remove(request.squad)
        }
        previous = present
        areas.entries.removeIf { (id, old) ->
            val next = present[id]
            next == null || next.faction != old.request.faction || next.point != old.request.point
        }
        rejected.keys.retainAll(present.keys)
        for (request in requests.sortedBy { it.squad }) {
            val old = areas[request.squad]
            if (old != null && request.radius <= old.request.radius) continue
            val updated = request.copy(radius = maxOf(request.radius, old?.request?.radius ?: 0.0))
            if (old != null && free(updated, old.center)) {
                areas[request.squad] = SquadArea(updated, old.center)
            } else {
                place(updated, ground)
            }
        }
    }

    fun reject(squad: UUID, ground: (SquadAreaRequest, Vec3) -> Vec3?): SquadArea? {
        val old = areas.remove(squad) ?: return null
        rejected.getOrPut(squad) { linkedSetOf() } += old.center
        place(old.request, ground)
        return areas[squad]
    }

    private fun place(request: SquadAreaRequest, ground: (SquadAreaRequest, Vec3) -> Vec3?) {
        if (request.nudge) return nudge(request, ground)
        val old = areas.remove(request.squad)
        val occupied = areas.values.filter { it.request.faction == request.faction }
        val stride = request.radius + (occupied.maxOfOrNull { it.request.radius } ?: request.radius) + GAP
        // Large patrol areas still need one full spacing step. A fixed distance cap smaller
        // than both radii would silently put every large squad back on the requested point.
        val maximumOffset = maxOf(MAX_OFFSET, stride)
        val offsets = listOf(Vec3.ZERO) + (1..2).flatMap { ring ->
            (0 until 16).map { index ->
                val angle = index * Math.PI / 8.0
                Vec3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)).scale(stride * ring)
            }
        }
        val next = offsets.asSequence().filter { it.horizontalDistance() <= maximumOffset }
            .mapNotNull { ground(request, request.point.add(it)) }
            .firstOrNull { it !in rejected[request.squad].orEmpty() && free(request, it) }
        if (next != null) {
            areas[request.squad] = SquadArea(request, next)
        } else if (old != null) {
            areas[request.squad] = old
        }
    }

    /**
     * Pushes a patrol's area straight off each area it overlaps, by the overlap alone. A whole
     * spacing step sent a squad patrolling 66 blocks from another's point 80 blocks into a forest,
     * away from where it was ordered; with no free ground that near, it keeps its point and overlaps.
     */
    private fun nudge(request: SquadAreaRequest, ground: (SquadAreaRequest, Vec3) -> Vec3?) {
        areas.remove(request.squad)
        var point = request.point
        repeat(NUDGE_PASSES) {
            val other = areas.values.firstOrNull { conflicts(request, point, it) } ?: return@repeat
            val away = point.subtract(other.center).multiply(1.0, 0.0, 1.0).let { if (it.lengthSqr() < NUDGE_MARGIN * NUDGE_MARGIN) Vec3(1.0, 0.0, 0.0) else it.normalize() }
            val spacing = request.radius + other.request.radius + GAP + NUDGE_MARGIN
            point = Vec3(other.center.x + away.x * spacing, request.point.y, other.center.z + away.z * spacing)
        }
        val placed = ground(request, point)?.takeIf { it !in rejected[request.squad].orEmpty() && free(request, it) }
        areas[request.squad] = SquadArea(request, placed ?: request.point)
    }

    private fun conflicts(request: SquadAreaRequest, point: Vec3, area: SquadArea) =
        area.request.squad != request.squad && area.request.faction == request.faction &&
            point.subtract(area.center).horizontalDistance() < request.radius + area.request.radius + GAP

    private fun free(request: SquadAreaRequest, point: Vec3): Boolean =
        areas.values.none {
            it.request.squad != request.squad && it.request.faction == request.faction &&
                point.subtract(it.center).horizontalDistance() < request.radius + it.request.radius + GAP
        }

    companion object {
        const val GAP = 6.0
        const val MAX_OFFSET = 160.0
        private const val NUDGE_PASSES = 3
        private const val NUDGE_MARGIN = 0.01
    }
}
