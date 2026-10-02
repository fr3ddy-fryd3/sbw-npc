package com.sbwnpc.squad.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class MarchProgressTest {
    private fun line(from: Int, to: Int) = (from..to).map { BlockPos(it, 64, 0) }
    private fun at(x: Int) = Vec3(x + 0.5, 64.5, 0.5)

    @Test
    fun `a leg searched from before the old end does not append a backwards edge`() {
        val progress = MarchProgress(2000)
        progress.append(line(0, 100))
        val rear = UUID.randomUUID()
        assertEquals(20, progress.index(rear, at(20)))
        progress.append(line(92, 180))
        assertEquals(line(0, 180), progress.route)
        assertEquals(20, progress.index(rear, at(20)))
    }

    @Test
    fun `a walker does not move its cursor back when displaced`() {
        val progress = MarchProgress(2000)
        progress.append(line(0, 200))
        val walker = UUID.randomUUID()
        assertEquals(80, progress.index(walker, at(80)))
        assertEquals(90, progress.index(walker, at(90)))
        assertEquals(90, progress.index(walker, at(75)))
    }

    @Test
    fun `a crossing keeps the current leg instead of jumping to the later identical node`() {
        val progress = MarchProgress(2000)
        val outward = line(0, 80)
        val back = (79 downTo 0).map { BlockPos(it, 64, 0) }
        progress.append(outward + back)
        val walker = UUID.randomUUID()
        assertEquals(5, progress.index(walker, at(5)))
        assertEquals(6, progress.index(walker, at(6)))
        progress.append(listOf(BlockPos(0, 64, 0), BlockPos(0, 64, 1)))
        assertEquals(outward.size + back.size + 1, progress.route.size)
    }

    @Test
    fun `dropping the old prefix preserves the front's position on the route`() {
        val progress = MarchProgress(100)
        progress.append(line(0, 99))
        val walker = UUID.randomUUID()
        progress.index(walker, at(90))
        progress.append(line(99, 149))
        assertEquals(BlockPos(50, 64, 0), progress.route.first())
        assertEquals(40, progress.index(walker, at(90)))
        assertTrue(progress.route.size <= 100)
    }
}
