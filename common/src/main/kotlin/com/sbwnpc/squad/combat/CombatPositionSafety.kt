package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/** Movement clearance from known enemies, independent of detection and weapon range. */
object CombatPositionSafety {
    private const val POSITION_TOLERANCE = 0.05
    private const val PATH_NODE_TOLERANCE = 0.75
    const val APPROACH_MARGIN = 2.0
    const val FLANK_MARGIN = 8.0

    /**
     * How much nearer a sentry on his height post may step for cover while the enemy is already
     * inside his clearance (#265). Without it a parapet on the near edge of a lookout was refused
     * as an approach, and he stood in the open firing from the far edge.
     */
    const val COVER_STEP = 4.0

    /** The cover step never brings a man nearer than this: no post in the enemy's face. */
    const val COVER_STEP_FLOOR = 24.0

    /** The nearest a point may lie to [threat] for a man at [from]; [step] lets a sentry close in for cover. */
    fun bound(from: Vec3, threat: Vec3, clearance: Double, step: Double = 0.0): Double {
        val distance = from.subtract(threat).horizontalDistance()
        // Outside the clearance the clearance itself holds: the step is only for an enemy already inside it.
        return if (step <= 0.0 || distance >= clearance) minOf(clearance, distance) else minOf(distance, maxOf(COVER_STEP_FLOOR, distance - step))
    }

    fun clearance(role: NpcClass): Double = maxOf(role.combatClearance, role.attackStandoffDistance)

    /** [clearance] for [role], cut in an assault to its stand-off, see [com.sbwnpc.squad.ai.tactics.assaultStandoff]. */
    fun clearance(role: NpcClass, assault: Boolean): Double = if (assault) minOf(clearance(role), com.sbwnpc.squad.ai.tactics.assaultStandoff(role == NpcClass.SHOTGUNNER)) else clearance(role)

    /** An approaching enemy does not force a defender to leave; further approach is forbidden. */
    fun permits(from: Vec3, point: Vec3, threat: Vec3, clearance: Double, step: Double = 0.0): Boolean = point.subtract(threat).horizontalDistance() >= bound(from, threat, clearance, step) - POSITION_TOLERANCE

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
    fun permitsSegment(origin: Vec3, from: Vec3, to: Vec3, threat: Vec3, clearance: Double, tolerance: Double = PATH_NODE_TOLERANCE): Boolean = clears(from, to, threat, bound(origin, threat, clearance) - tolerance)

    private fun clears(from: Vec3, to: Vec3, threat: Vec3, limit: Double): Boolean {
        val segment = to.subtract(from).multiply(1.0, 0.0, 1.0)
        val length = segment.lengthSqr()
        val fraction = if (length < 1.0e-9) 0.0 else threat.subtract(from).dot(segment).div(length).coerceIn(0.0, 1.0)
        val nearest = from.add(segment.scale(fraction))
        return nearest.subtract(threat).horizontalDistance() >= limit
    }

    fun permitsPath(origin: Vec3, path: Path, threats: List<Vec3>, clearance: Double, step: Double = 0.0): Boolean {
        var previous = origin
        for (index in path.nextNodeIndex until path.nodeCount) {
            val node = path.getNode(index)
            val point = Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
            // Path nodes use block centres; allow their sub-block rounding at an occupied post.
            if (threats.any { !clears(previous, point, it, bound(origin, it, clearance, step) - PATH_NODE_TOLERANCE) }) return false
            previous = point
        }
        return true
    }
}
