package com.sbwnpc.squad.entity.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class OpeningFireTest {
    private val target = UUID.randomUUID()

    @Test
    fun `automatic gun aims and fires its first burst before optional relocation`() {
        val contact = OpeningFire()
        assertTrue(contact.hold(target, 0, shootable = true, automatic = true))
        assertTrue(contact.hold(target, 55, shootable = true, automatic = true))
        repeat(3) { contact.fired() }
        assertTrue(contact.hold(target, 60, shootable = true, automatic = true))
        contact.fired()
        assertFalse(contact.hold(target, 61, shootable = true, automatic = true))
    }

    @Test
    fun `a semi automatic gun only needs one opening shot`() {
        val contact = OpeningFire()
        assertTrue(contact.hold(target, 0, shootable = true, automatic = false))
        contact.fired()
        assertFalse(contact.hold(target, 1, shootable = true, automatic = false))
    }

    @Test
    fun `a hidden contact can be approached and only starts the fire pause when sighted`() {
        val contact = OpeningFire()
        assertFalse(contact.hold(target, 0, shootable = false, automatic = true))
        assertTrue(contact.hold(target, 200, shootable = true, automatic = true))
        assertTrue(contact.hold(target, 255, shootable = true, automatic = true))
    }

    @Test
    fun `a blocked opening shot cannot freeze movement forever and a new contact gets its own burst`() {
        val contact = OpeningFire()
        assertTrue(contact.hold(target, 0, shootable = true, automatic = true))
        assertFalse(contact.hold(target, 100, shootable = true, automatic = true))
        assertTrue(contact.hold(UUID.randomUUID(), 101, shootable = true, automatic = true))
    }
}
