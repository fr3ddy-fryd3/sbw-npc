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

    @Test
    fun `player takes one real seat without double counting mounted NPCs`() {
        val player = UUID.randomUUID()
        VehicleTransportClaims.claimDriver(vehicle, driver)
        val aboard = listOf(driver, player)
        assertEquals(2, VehicleTransportClaims.occupiedOrClaimedSeats(vehicle, aboard))
        assertTrue(VehicleTransportClaims.claimPassenger(vehicle, rider, 3, aboard))
        assertEquals(3, VehicleTransportClaims.occupiedOrClaimedSeats(vehicle, aboard + rider))
        assertFalse(VehicleTransportClaims.claimPassenger(vehicle, UUID.randomUUID(), 3, aboard + rider))
    }

    @Test
    fun `a player using the last free seat blocks new claims but dismount frees it`() {
        VehicleTransportClaims.claimDriver(vehicle, driver)
        val player = UUID.randomUUID()
        assertFalse(VehicleTransportClaims.claimPassenger(vehicle, rider, 2, listOf(driver, player)))
        assertNull(VehicleTransportClaims.vehicleOf(rider))
        assertTrue(VehicleTransportClaims.claimPassenger(vehicle, rider, 2, listOf(driver)))
    }

    @Test
    fun `mounted crew can restore its own claim in a full vehicle`() {
        VehicleTransportClaims.claimDriver(vehicle, driver)
        assertTrue(VehicleTransportClaims.claimPassenger(vehicle, rider, 2, listOf(driver, rider)))
        assertEquals(2, VehicleTransportClaims.occupiedOrClaimedSeats(vehicle, listOf(driver, rider)))
    }
}
