package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity

/** Known armour never authorizes blind small-arms fire or a hand-thrown grenade. */
object ArmourFireSafety {
    /** A stored fire bearing through known armour is not a blind-fire or grenade destination. */
    fun bearingContainsArmour(npc: NpcEntity, aim: net.minecraft.world.phys.Vec3): Boolean {
        val now = npc.level().gameTime
        return npc.currentSquad()?.tactics?.contacts?.values.orEmpty().any { contact ->
            if (!contact.armoured || contact.airborne || !SquadKnowledge.known(contact.seenAt, contact.firstSeenAt, now, npc.uuid in contact.seenBy)) return@any false
            contains(npc.position(), aim, contact.position)
        }
    }

    /** A finite horizontal shot segment, including the short backstop beside its intended point. */
    internal fun contains(from: net.minecraft.world.phys.Vec3, aim: net.minecraft.world.phys.Vec3, armour: net.minecraft.world.phys.Vec3): Boolean {
        val segment = aim.subtract(from).multiply(1.0, 0.0, 1.0)
        val distance = segment.length()
        val bearing = segment.normalize()
        val delta = armour.subtract(from).multiply(1.0, 0.0, 1.0)
        val along = delta.dot(bearing)
        return along > 0.0 && along <= distance + ARMOUR_BEARING_WIDTH && delta.subtract(bearing.scale(along)).length() <= ARMOUR_BEARING_WIDTH + along * ARMOUR_BEARING_ERROR
    }

    private const val ARMOUR_BEARING_WIDTH = 4.0
    private const val ARMOUR_BEARING_ERROR = 0.1
}
