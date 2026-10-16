package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.tactics.operation.AttackRouteRetry
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttackRouteRetryTest {
    @Test fun `deferred admissions retain the probe and exhaustion cannot restart itself`() {
        val retry = AttackRouteRetry()
        val from = Vec3(0.0, 64.0, 0.0)
        val target = Vec3(100.0, 64.0, 0.0)
        retry.blocked(target, 0)
        assertNull(retry.candidate(from, 199))
        val first = retry.candidate(from, 200)
        repeat(100) { assertEquals(first, retry.candidate(from, 200), "Waiting for native admission consumed a probe") }
        var now = 200L
        var attempts = 0
        while (retry.nextAt != Long.MAX_VALUE && attempts < 100) {
            now = maxOf(now, retry.nextAt)
            val point = retry.candidate(from, now)!!
            assertTrue(point.distanceTo(from) <= 46.0, "Recovery constructed an unbounded path request")
            retry.rejected(now)
            attempts++
        }
        assertTrue(attempts in 1..64, "Native failures must lead to finite exhaustion")
        retry.blocked(target, now + 1000)
        assertNull(retry.candidate(from, now + 10000), "An exhausted route silently restarted its search")
        retry.settled()
        retry.blocked(target, now + 1000)
        assertNotNull(retry.candidate(from, now + 1200), "A genuinely completed old route prevented recovery of a new one")
    }
}
