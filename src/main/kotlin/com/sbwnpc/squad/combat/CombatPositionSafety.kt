package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/** Movement clearance from known enemies, independent of detection and weapon range. */
object CombatPositionSafety {
    private const val INFANTRY_CLEARANCE = 48.0
    private const val POSITION_TOLERANCE = 0.05
    private const val PATH_NODE_TOLERANCE = 0.75
    const val APPROACH_MARGIN = 2.0
    const val FLANK_MARGIN = 8.0

    fun clearance(role: NpcClass): Double = maxOf(INFANTRY_CLEARANCE, role.attackStandoffDistance)

    /** An approaching enemy does not force a defender to leave; further approach is forbidden. */
    fun permits(from: Vec3, point: Vec3, threat: Vec3, clearance: Double): Boolean = point.subtract(threat).horizontalDistance() >= minOf(clearance, from.subtract(threat).horizontalDistance()) - POSITION_TOLERANCE

    /** Shorten an assault bound before it crosses the standoff circle. */
    fun approach(from: Vec3, point: Vec3, threat: Vec3, clearance: Double): Vec3 {
        if (permitsSegment(from, from, point, threat, clearance, POSITION_TOLERANCE)) return point
        var near = 0.0
        var far = 1.0
        repeat(20) {
            val fraction = (near + far) / 2.0
            val probe = from.add(point.subtract(from).scale(fraction))
            if (permitsSegment(from, from, probe, threat, clearance, POSITION_TOLERANCE)) near = fraction else far = fraction
        }
        val stopped = from.add(point.subtract(from).scale(near))
        return Vec3(stopped.x, point.y, stopped.z)
    }

    /** Check segments as well as nodes: two safe endpoints can cut across the enemy's radius. */
    fun permitsSegment(origin: Vec3, from: Vec3, to: Vec3, threat: Vec3, clearance: Double, tolerance: Double = PATH_NODE_TOLERANCE): Boolean {
        val segment = to.subtract(from).multiply(1.0, 0.0, 1.0)
        val length = segment.lengthSqr()
        val fraction = if (length < 1.0e-9) 0.0 else threat.subtract(from).dot(segment).div(length).coerceIn(0.0, 1.0)
        val nearest = from.add(segment.scale(fraction))
        // Path nodes use block centres; allow their sub-block rounding at an occupied post.
        return nearest.subtract(threat).horizontalDistance() >= minOf(clearance, origin.subtract(threat).horizontalDistance()) - tolerance
    }

    fun permitsPath(origin: Vec3, path: Path, threats: List<Vec3>, clearance: Double): Boolean {
        var previous = origin
        for (index in path.nextNodeIndex until path.nodeCount) {
            val node = path.getNode(index)
            val point = Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
            if (threats.any { !permitsSegment(origin, previous, point, it, clearance) }) return false
            previous = point
        }
        return true
    }
}
