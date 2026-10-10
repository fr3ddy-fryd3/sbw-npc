package com.sbwnpc.squad.entity.ai

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CombatPathRetryTest {
    @Test fun `failed local route waits briefly but changed movement intent retries immediately`() {
        val retry = CombatPathRetry()
        val request = CombatPathRetry.Request(Vec3.ZERO, Vec3(24.0, 0.0, 0.0), UUID(0, 1), Vec3(100.0, 0.0, 0.0))
        assertTrue(retry.permits(request, 10))
        retry.reject(request, 10)
        assertFalse(retry.permits(request, 19))
        assertTrue(retry.permits(request, 20), "A changed world must never stay negatively cached")
        for (changed in listOf(
            request.copy(from = Vec3(0.6, 0.0, 0.0)),
            request.copy(goal = request.goal.add(0.0, 1.0, 0.0)),
            request.copy(target = UUID(0, 2)),
            request.copy(threat = request.threat.add(0.0, 0.0, 2.0)),
        )) {
            assertTrue(retry.permits(changed, 11))
        }
        retry.reset()
        assertTrue(retry.permits(request, 11), "New orders must not inherit the old failure")
    }
}
