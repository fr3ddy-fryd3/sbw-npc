package com.sbwnpc.squad.entity.ai

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class VehicleTransportClaimsTest {
    private val vehicle = UUID.randomUUID()
    private val driver = UUID.randomUUID()
    private val rider = UUID.randomUUID()

    @BeforeEach fun reset() = VehicleTransportClaims.clearAll()
    @AfterEach fun cleanup() = VehicleTransportClaims.clearAll()

    @Test
    fun `an NPC can reserve only one seat and death frees it`() {
        assertTrue(VehicleTransportClaims.claimDriver(vehicle, driver))
        assertTrue(VehicleTransportClaims.claimPassenger(UUID.randomUUID(), driver, 3))
        assertNull(VehicleTransportClaims.driverOf(vehicle))

        VehicleTransportClaims.release(driver)
        assertTrue(VehicleTransportClaims.claimDriver(vehicle, rider))
    }

    @Test
    fun `capacity counts driver and passengers`() {
        VehicleTransportClaims.claimDriver(vehicle, driver)
        assertTrue(VehicleTransportClaims.claimPassenger(vehicle, rider, 2))
        assertEquals(2, VehicleTransportClaims.claimedSeats(vehicle))
        assertFalse(VehicleTransportClaims.claimPassenger(vehicle, UUID.randomUUID(), 2))
    }
}
