package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SeatAllocationTest {
    private val men = (1..6).map { UUID(0, it.toLong()) }
    private val truck = UUID(1, 1)
    private val jeep = UUID(1, 2)

    private fun rider(i: Int, x: Double) = SeatRider(men[i], Point3(x, 64.0, 0.0))
    private fun vehicle(id: UUID, x: Double, free: Int, driven: Boolean = false, ours: Boolean = false) = SeatVehicle(id, Point3(x, 64.0, 0.0), free, driven, ours)

    @Test fun `the squad keeps together in the roomiest vehicle and the man nearest it drives`() {
        val riders = (0..3).map { rider(it, it * 2.0) }
        val plan = SeatAllocation.allocate(SeatPlan.EMPTY, riders, listOf(vehicle(jeep, 0.0, 2), vehicle(truck, 20.0, 6)))
        assertTrue(plan.seats.values.all { it.vehicle == truck })
        assertEquals(listOf(men[3]), plan.seats.filterValues { it.driver }.keys.toList())
        assertTrue(plan.walks.isEmpty())
    }

    @Test fun `men beyond the first vehicle's room take the next one, which gets its own driver`() {
        val riders = (0..3).map { rider(it, 0.0) }
        val plan = SeatAllocation.allocate(SeatPlan.EMPTY, riders, listOf(vehicle(jeep, 5.0, 2), vehicle(truck, 5.0, 3)))
        assertEquals(3, plan.seats.values.count { it.vehicle == truck })
        assertEquals(1, plan.seats.values.count { it.vehicle == jeep })
        assertEquals(1, plan.seats.values.count { it.vehicle == jeep && it.driver })
        assertEquals(1, plan.seats.values.count { it.vehicle == truck && it.driver })
    }

    @Test fun `a vehicle already driven gets no second driver`() {
        val plan = SeatAllocation.allocate(SeatPlan.EMPTY, listOf(rider(0, 0.0)), listOf(vehicle(truck, 5.0, 3, driven = true)))
        assertEquals(RideSeat(truck, driver = false), plan.seats[men[0]])
    }

    @Test fun `a man keeps his seat as the others walk over`() {
        val first = SeatAllocation.allocate(SeatPlan.EMPTY, listOf(rider(0, 0.0)), listOf(vehicle(jeep, 30.0, 2), vehicle(truck, 50.0, 2)))
        val seat = first.seats.getValue(men[0])
        val later = SeatAllocation.allocate(first, listOf(rider(0, 49.0), rider(1, 0.0)), listOf(vehicle(jeep, 30.0, 2), vehicle(truck, 50.0, 2)))
        assertEquals(seat, later.seats[men[0]])
    }

    @Test fun `a man left behind takes his squad's vehicle before a nearer free one`() {
        val plan = SeatAllocation.allocate(
            SeatPlan.EMPTY,
            listOf(rider(0, 0.0)),
            listOf(vehicle(jeep, 3.0, 4), vehicle(truck, 30.0, 3, driven = true, ours = true)),
        )
        assertEquals(RideSeat(truck, driver = false), plan.seats[men[0]])
    }

    @Test fun `a man refused a vehicle is given another, or walks`() {
        val vehicles = listOf(vehicle(truck, 5.0, 4), vehicle(jeep, 10.0, 2))
        val first = SeatAllocation.allocate(SeatPlan.EMPTY, listOf(rider(0, 0.0)), vehicles)
        assertEquals(truck, first.seats.getValue(men[0]).vehicle)
        val again = SeatAllocation.allocate(first, listOf(rider(0, 0.0)), vehicles, mapOf(men[0] to setOf(truck)))
        assertEquals(RideSeat(jeep, driver = true), again.seats[men[0]])
        val none = SeatAllocation.allocate(again, listOf(rider(0, 0.0)), vehicles, mapOf(men[0] to setOf(truck, jeep)))
        assertEquals(setOf(men[0]), none.walks)
    }

    @Test fun `a man with no seat in reach walks for the order and stays walking`() {
        val first = SeatAllocation.allocate(SeatPlan.EMPTY, listOf(rider(0, 0.0), rider(1, 0.0)), listOf(vehicle(jeep, 10.0, 1), vehicle(truck, 200.0, 4)))
        assertEquals(1, first.seats.size)
        assertEquals(1, first.walks.size)
        val later = SeatAllocation.allocate(first, listOf(rider(0, 0.0), rider(1, 0.0)), listOf(vehicle(jeep, 10.0, 1), vehicle(truck, 10.0, 4)))
        assertEquals(first, later)
    }

    @Test fun `a man gone from the squad on foot frees his seat`() {
        val first = SeatAllocation.allocate(SeatPlan.EMPTY, listOf(rider(0, 0.0)), listOf(vehicle(jeep, 5.0, 1)))
        val later = SeatAllocation.allocate(first, listOf(rider(1, 0.0)), listOf(vehicle(jeep, 5.0, 1)))
        assertEquals(mapOf(men[1] to RideSeat(jeep, driver = true)), later.seats)
    }
}
