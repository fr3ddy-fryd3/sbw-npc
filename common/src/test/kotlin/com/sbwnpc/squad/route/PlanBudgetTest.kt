package com.sbwnpc.squad.route

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanBudgetTest {
    private val plain = object : Ground {
        override fun known(x: Int, z: Int) = true
        override fun kind(x: Int, z: Int) = GroundMap.Kind.GROUND
        override fun height(x: Int, z: Int) = 64
        override fun room(x: Int, z: Int) = 4
    }

    private fun search(z: Double) = CellPlanner.search(Walking(plain), Vec3(0.5, 64.0, z), Vec3(400.5, 64.0, z))!!

    @AfterEach
    fun clear() = PlanBudget.clearAll()

    @Test
    fun `the first search started finishes before the later ones are begun on`() {
        val searches = (0 until 6).map { search(it * 40.5) }
        var tick = 0
        searches.forEach { PlanBudget.advance(it, tick) }
        while (!searches.first().finished) {
            searches.forEach { PlanBudget.advance(it, tick) }
            PlanBudget.tick(tick)
            assertTrue(++tick < 1000)
        }
        // Shared evenly, all six came back together; in turn, the first is done while the last is untouched.
        assertFalse(searches.last().finished)
        assertTrue(searches.last().expanded == 0, "the last search was worked on before the first was done")
    }

    @Test
    fun `a search nobody asks after waits behind those still wanted`() {
        val forgotten = search(0.5)
        val wanted = search(80.5)
        // Last asked after 100 ticks ago: stale, but not yet dropped.
        PlanBudget.advance(forgotten, -100)
        var tick = 0
        while (!wanted.finished) {
            PlanBudget.advance(wanted, tick)
            PlanBudget.tick(tick)
            assertTrue(++tick < 1000)
        }
        assertFalse(forgotten.finished, "a stale search took the budget ahead of a wanted one")
    }
}
