package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SidestepDistanceTest {
    @Test fun `each spent batch lengthens the step by one plain step up to the cap`() {
        assertEquals(2.5, FriendlyFireGuard.sidestepDistance(0))
        assertEquals(5.0, FriendlyFireGuard.sidestepDistance(1))
        assertEquals(7.5, FriendlyFireGuard.sidestepDistance(2))
        assertEquals(12.0, FriendlyFireGuard.sidestepDistance(4))
        assertEquals(12.0, FriendlyFireGuard.sidestepDistance(40))
    }
}
