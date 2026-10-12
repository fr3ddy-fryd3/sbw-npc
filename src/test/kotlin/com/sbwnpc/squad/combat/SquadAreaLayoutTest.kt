package com.sbwnpc.squad.combat

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SquadAreaLayoutTest {
    private fun request(id: Long, x: Double = 0.0, radius: Double = 31.0, faction: SquadFaction = SquadFaction.PIG) = SquadAreaRequest(UUID(0, id), faction, Vec3(x, 64.0, 0.0), radius)
    private val flat: (SquadAreaRequest, Vec3) -> Vec3? = { _, point -> point }

    @Test fun `coincident and nearby orders receive nonoverlapping areas while distant and hostile orders keep their points`() {
        val layout = SquadAreaLayout()
        val requests = listOf(request(1), request(2, 12.0), request(3), request(4, 400.0), request(5, faction = SquadFaction.COW))
        layout.reconcile(requests, flat)
        for (a in requests.take(3)) {
            for (b in requests.take(3)) {
                if (a.squad == b.squad) continue
                assertTrue(layout.area(a.squad)!!.center.distanceTo(layout.area(b.squad)!!.center) >= a.radius + b.radius + SquadAreaLayout.GAP)
            }
        }
        assertEquals(requests[3].point, layout.area(requests[3].squad)!!.center)
        assertEquals(requests[4].point, layout.area(requests[4].squad)!!.center)
    }

    @Test fun `casualties joins and removals preserve existing centers and vacancies are reusable`() {
        val layout = SquadAreaLayout()
        val requests = listOf(request(1), request(2), request(3))
        layout.reconcile(requests, flat)
        val before = requests.associate { it.squad to layout.area(it.squad)!!.center }
        layout.reconcile(listOf(requests[1].copy(radius = 12.0), requests[2], request(4)), flat)
        assertEquals(before[requests[1].squad], layout.area(requests[1].squad)!!.center)
        assertEquals(before[requests[2].squad], layout.area(requests[2].squad)!!.center)
        assertEquals(before[requests[0].squad], layout.area(UUID(0, 4))!!.center)
    }

    @Test fun `blocked candidates and unreachable areas are replaced without forgetting their rejection on refresh`() {
        val layout = SquadAreaLayout()
        val requests = listOf(request(1), request(2))
        val ground: (SquadAreaRequest, Vec3) -> Vec3? = { _, p -> p.takeIf { it.x < 40.0 } }
        layout.reconcile(requests, ground)
        val old = layout.area(requests[1].squad)!!.center
        val next = layout.reject(requests[1].squad, ground)!!
        assertNotEquals(old, next.center)
        assertTrue(next.center.x < 40.0)
        layout.reconcile(requests, ground)
        assertEquals(next.center, layout.area(requests[1].squad)!!.center)
        layout.reconcile(listOf(requests[0], request(2, 400.0)), ground)
        assertNull(layout.area(requests[1].squad))
    }

    @Test fun `terrain validation is bounded and an unavailable area is not claimed as successful`() {
        val layout = SquadAreaLayout()
        var probes = 0
        layout.reconcile(listOf(request(1))) { _, _ ->
            probes++
            null
        }
        assertNull(layout.area(UUID(0, 1)))
        assertTrue(probes <= 33)
    }
}
