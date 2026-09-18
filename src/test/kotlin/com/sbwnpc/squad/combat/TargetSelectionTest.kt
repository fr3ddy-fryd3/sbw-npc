package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TargetSelectionTest {
    private data class Candidate(val distance: Double, val visible: Boolean)

    @Test
    fun `selection matches exhaustive visibility checks including ties and occluded targets`() {
        val random = Random(731)
        repeat(1000) {
            val candidates = List(random.nextInt(100)) {
                Candidate(random.nextInt(20).toDouble(), random.nextBoolean())
            }
            val expected = candidates.filter { it.visible }.minByOrNull { it.distance }
            assertSame(expected, TargetSelection.nearestVisible(candidates, { it.distance }, { it.visible }))
        }
    }

    @Test
    fun `near visible target avoids every farther visibility check`() {
        val candidates = (1..10_000).map { Candidate(it.toDouble(), true) }
        var visibilityChecks = 0
        val chosen = TargetSelection.nearestVisible(candidates, { it.distance }) {
            visibilityChecks++
            it.visible
        }
        assertSame(candidates.first(), chosen)
        assertEquals(1, visibilityChecks)
    }

    @Test
    fun `occluded nearby targets never mask a farther visible target`() {
        val candidates = (1..100).map { Candidate(it.toDouble(), it == 100) }
        var visibilityChecks = 0
        assertSame(candidates.last(), TargetSelection.nearestVisible(candidates, { it.distance }) {
            visibilityChecks++
            it.visible
        })
        assertEquals(100, visibilityChecks)
    }
}
