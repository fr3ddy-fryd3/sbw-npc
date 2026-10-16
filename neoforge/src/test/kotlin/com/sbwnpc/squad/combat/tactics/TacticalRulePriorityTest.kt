package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalRulePriorityTest {
    private val center = Vec3(0.0, 64.0, 0.0)
    private val members = (0L until 8L).map { TacticalMember(UUID(0, it), center) }
    private val contact = TacticalContact(UUID(1, 0), Vec3(45.0, 64.0, 0.0), 0)
    private val attack = TacticalSnapshot(0, SquadOrder.ATTACK, 1, center, Vec3(80.0, 64.0, 0.0), members, listOf(contact))

    @Test fun `overlapping situations retain their decision priority`() {
        val armour = contact.copy(armoured = true)
        val rear = contact.copy(id = UUID(1, 1), position = Vec3(-45.0, 64.0, 0.0))
        val moving = contact.copy(velocity = Vec3(0.2, 0.0, 0.0))
        val highRemembered = contact.copy(position = Vec3(45.0, 72.0, 0.0), seenAt = -40)
        val cases = listOf(
            attack.copy(order = SquadOrder.RETREAT, grenade = true) to TacticalReason.GRENADE_DANGER,
            attack.copy(order = SquadOrder.RETREAT, members = members.take(1), peakStrength = 8) to TacticalReason.PLAYER_RETREAT_OR_BARRAGE,
            attack.copy(contacts = listOf(highRemembered), incoming = listOf(rear.position)) to TacticalReason.LAST_CONTACT_HIGHER,
            attack.copy(contacts = listOf(contact.copy(seenAt = -40)), incoming = listOf(rear.position)) to TacticalReason.UNSEEN_INCOMING_FIRE,
            attack.copy(contacts = emptyList(), members = members.take(1), peakStrength = 8) to TacticalReason.NO_CONTACT,
            attack.copy(contacts = listOf(armour, rear), members = members.take(1), peakStrength = 8) to TacticalReason.LOW_STRENGTH,
            attack.copy(contacts = listOf(armour, rear)) to TacticalReason.MULTIPLE_FRONTS,
            attack.copy(contacts = listOf(armour.copy(position = center.add(4.0, 0.0, 0.0)))) to TacticalReason.ARMOURED_CONTACT,
            attack.copy(contacts = listOf(contact.copy(position = center.add(4.0, 0.0, 0.0))), members = members.take(1), peakStrength = 1) to TacticalReason.CLOSE_OR_APPROACHING_ENEMY,
            attack.copy(order = SquadOrder.DEFEND, contacts = listOf(contact.copy(position = Vec3(45.0, 56.0, 0.0)))) to TacticalReason.ENEMY_BELOW,
            attack.copy(narrow = true, stalled = true, contacts = listOf(moving)) to TacticalReason.NARROW_ROUTE,
            attack.copy(stalled = true, contacts = listOf(moving)) to TacticalReason.BLOCKED_SIGHT,
            attack.copy(contacts = listOf(moving, moving.copy(id = UUID(1, 2), position = Vec3(80.0, 64.0, 0.0)))) to TacticalReason.ENEMY_WITHDRAWING,
            attack.copy(contacts = listOf(contact, contact.copy(id = UUID(1, 2), position = Vec3(80.0, 64.0, 0.0)))) to TacticalReason.SPREAD_ENEMY_FRONT,
            attack.copy(contacts = listOf(contact.copy(priority = 2.0))) to TacticalReason.SMALL_COMPACT_ENEMY,
        )
        for ((snapshot, expectedReason) in cases) {
            assertEquals(expectedReason, TacticalRules.choose(snapshot).reason, "Overlapping conditions for $expectedReason")
        }
    }

    @Test fun `focus chooses priority adjusted by range and then the latest remembered contact`() {
        val distant = contact.copy(id = UUID(1, 2), position = Vec3(180.0, 64.0, 0.0), priority = 4.0)
        val near = contact.copy(position = Vec3(20.0, 64.0, 0.0), priority = 2.0)
        assertEquals(near.position, TacticalRules.choose(attack.copy(grenade = true, contacts = listOf(distant, near))).focus)
        val remembered = listOf(distant.copy(seenAt = -50), near.copy(seenAt = -70))
        assertEquals(distant.position, TacticalRules.choose(attack.copy(contacts = remembered)).focus)
    }

    @Test fun `height threshold is inclusive while approaching speed threshold is exclusive`() {
        val justBelowHeight = contact.copy(position = Vec3(45.0, 71.999, 0.0))
        val atHeight = contact.copy(position = Vec3(45.0, 72.0, 0.0))
        assertEquals(TacticalPattern.ENCIRCLE, TacticalRules.choose(attack.copy(contacts = listOf(justBelowHeight))).pattern)
        assertEquals(TacticalPattern.ATTACK_HEIGHT, TacticalRules.choose(attack.copy(contacts = listOf(atHeight))).pattern)

        val approaching = contact.copy(position = Vec3(18.0, 64.0, 0.0), velocity = Vec3(-0.08, 0.0, 0.0))
        assertEquals(TacticalPattern.ENCIRCLE, TacticalRules.choose(attack.copy(contacts = listOf(approaching))).pattern)
        assertEquals(TacticalPattern.REPEL, TacticalRules.choose(attack.copy(contacts = listOf(approaching.copy(velocity = Vec3(-0.081, 0.0, 0.0))))).pattern)
    }
}
