package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/** A long trip has no time limit while it reaches fresh ground; shuttling in place doesn't count. */
internal class GroundTravelProgress {
    private val visited = LinkedHashSet<Long>()
    private var lastProgressTick = 0

    fun reset(tick: Int, position: Vec3) {
        visited.clear()
        lastProgressTick = tick
        observe(tick, position)
    }

    fun observe(tick: Int, position: Vec3) {
        val x = floor(position.x / 4).toInt()
        val z = floor(position.z / 4).toInt()
        val cell = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        if (visited.add(cell)) {
            lastProgressTick = tick
            if (visited.size > 8192) visited.remove(visited.first())
        }
    }

    fun stalled(tick: Int): Boolean = tick - lastProgressTick > 1200
}
