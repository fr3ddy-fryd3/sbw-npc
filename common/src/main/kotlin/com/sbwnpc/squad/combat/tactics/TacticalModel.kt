package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class TacticalJob {
    COVER,
    ADVANCE,
    OBSERVE,
    SEARCH,
    REGROUP,
    RETREAT,
    ANTI_ARMOUR,
    RESERVE,
    WAIT,
    OVERWATCH,
    ;

    companion object {
        val RUNNING = setOf(ADVANCE, REGROUP, RETREAT)
        val FIRE_SUPPORT = setOf(COVER, WAIT)
        val FIRING_POSITIONS = FIRE_SUPPORT + setOf(ANTI_ARMOUR, OVERWATCH)
    }
}

data class TacticalMember(
    val id: UUID,
    val position: Vec3,
    val role: NpcClass = NpcClass.RIFLEMAN,
    val health: Double = 1.0,
    val ready: Boolean = true,
    val canFire: Boolean = false,
    val suppressed: Boolean = false,
    val rockets: Boolean = false,
    val firingAt: Vec3? = null,
    val recentFire: Boolean = canFire,
    val recentFireAt: Vec3? = firingAt,
    val moving: Boolean = false,
    val grounded: Boolean = true,
    val primaryAmmo: Boolean = true,
    val sidearmOnly: Boolean = false,
)
data class TacticalContact(
    val id: UUID,
    val position: Vec3,
    val seenAt: Long,
    val velocity: Vec3 = Vec3.ZERO,
    val armoured: Boolean = false,
    val priority: Double = 1.0,
    val airborne: Boolean = false,
    /** When the squad first had this contact; the call to the others counts from here, see [com.sbwnpc.squad.combat.SquadKnowledge]. */
    val firstSeenAt: Long = seenAt,
    /** The men of the squad who noticed this enemy themselves: they need no call. */
    val seenBy: Set<UUID> = emptySet(),
)
data class TacticalSnapshot(
    val now: Long,
    val order: SquadOrder,
    val stamp: Int,
    val center: Vec3,
    val home: Vec3?,
    val members: List<TacticalMember>,
    val contacts: List<TacticalContact>,
    val incoming: List<Vec3> = emptyList(),
    val grenade: Boolean = false,
    val narrow: Boolean = false,
    val open: Boolean = false,
    val stalled: Boolean = false,
    val peakStrength: Int = members.size,
    val threatOrigin: Vec3? = null,
    val regrouping: Boolean? = null,
    val suppressionWithdrawal: Boolean = false,
    val hasPatrolRoute: Boolean = false,
) {
    val visible get() = contacts.filter { now - it.seenAt < 40 }
    val fighting get() = members.filter { it.ready && it.health >= 0.3 && (!offensive || it.primaryAmmo) }
    val offensive get() = order == SquadOrder.ATTACK
}
