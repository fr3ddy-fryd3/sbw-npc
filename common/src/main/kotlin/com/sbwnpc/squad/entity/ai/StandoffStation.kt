package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a gunship hovers to work its target: [range] out from it, on the side it comes from, unless
 * that spot lies within [range] of another known enemy. Measured from the target alone, the hover
 * point of a gunship laid on the farther of two men 25 apart came out 10 blocks from the nearer
 * one (#285): it hung over an enemy it was not fighting. Then it goes round the target to the
 * bearing farthest from the nearest of them, the shorter way round on a tie: the first bearing
 * just clear of them left it a few blocks of drift from hanging over him again.
 */
object StandoffStation {
    private const val BEARINGS = 24

    /** An enemy this close to the target is the target itself, seen again: no reason to move off. */
    private const val SAME_SPOT = 1.0

    fun point(heli: Vec3, target: Vec3, others: List<Vec3>, range: Double): Vec3 {
        val away = Vec3(heli.x - target.x, 0.0, heli.z - target.z)
        val dir = if (away.lengthSqr() < SAME_SPOT * SAME_SPOT) Vec3(0.0, 0.0, 1.0) else away.normalize()
        val direct = target.add(dir.scale(range))
        val threats = others.filter { horizontal(it, target) > SAME_SPOT }
        if (clearance(direct, threats) >= range) return direct
        val start = kotlin.math.atan2(dir.z, dir.x)
        // Nearest turn first, alternating sides: maxBy keeps the first of equals, the shorter way round.
        val candidates = (1..BEARINGS / 2).flatMap { step -> listOf(step, -step) }.map { step ->
            val angle = start + step * 2 * PI / BEARINGS
            target.add(cos(angle) * range, 0.0, sin(angle) * range)
        }
        return candidates.maxBy { clearance(it, threats) }
    }

    private fun clearance(point: Vec3, threats: List<Vec3>) = threats.minOfOrNull { horizontal(it, point) } ?: Double.MAX_VALUE

    private fun horizontal(a: Vec3, b: Vec3) = Vec3(a.x - b.x, 0.0, a.z - b.z).length()
}
