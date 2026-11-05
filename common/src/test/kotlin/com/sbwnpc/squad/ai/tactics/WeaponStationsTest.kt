package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class WeaponStationsTest {
    private val men = (1..3).map { UUID(0, it.toLong()) }
    private val tank = UUID(1, 1)
    private val lav = UUID(1, 2)

    private fun at(x: Double) = Point3(x, 64.0, 0.0)

    @Test fun `the nearest free man mans each station, one man to a vehicle`() {
        val hands = listOf(StationHand(men[0], at(0.0)), StationHand(men[1], at(8.0)), StationHand(men[2], at(30.0)))
        val plan = WeaponStations.assign(emptyMap(), listOf(WeaponStation(tank, at(10.0)), WeaponStation(lav, at(12.0))), hands)
        assertEquals(mapOf(tank to men[1], lav to men[0]), plan)
    }

    @Test fun `nobody walks past the reach to a gun`() {
        assertEquals(emptyMap<UUID, UUID>(), WeaponStations.assign(emptyMap(), listOf(WeaponStation(tank, at(50.0))), listOf(StationHand(men[0], at(0.0)))))
    }

    @Test fun `a man keeps his gun while both are there, and loses it when he is not free`() {
        val stations = listOf(WeaponStation(tank, at(10.0)))
        val first = WeaponStations.assign(emptyMap(), stations, listOf(StationHand(men[0], at(0.0))))
        assertEquals(mapOf(tank to men[0]), WeaponStations.assign(first, stations, listOf(StationHand(men[0], at(0.0)), StationHand(men[1], at(9.0)))))
        assertEquals(mapOf(tank to men[1]), WeaponStations.assign(first, stations, listOf(StationHand(men[1], at(9.0)))))
    }
}
