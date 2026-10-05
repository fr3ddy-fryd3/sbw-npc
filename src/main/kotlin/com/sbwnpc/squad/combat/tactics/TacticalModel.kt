package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class TacticalPattern {
    FOLLOW_ORDER, RETURN_FIRE, FLANK, ENCIRCLE, BOUND, FOCUS_SECTOR, DISLODGE, ATTACK_HEIGHT, HOLD_HEIGHT,
    FILE, REORIENT, BREAK_CONTACT, REPEL, SEARCH, PURSUE, REORGANIZE, ANTI_ARMOUR, AVOID_ARMOUR,
    EVADE, CONSOLIDATE
}
enum class TacticalStatus { PREPARING, EXECUTING, REGROUPING, COMPLETED, FAILED }
enum class TacticalJob {
    COVER, ADVANCE, FLANK, OBSERVE, SEARCH, REGROUP, RETREAT, ANTI_ARMOUR, RESERVE, WAIT, OVERWATCH;
    companion object { val RUNNING = setOf(ADVANCE, FLANK, REGROUP, RETREAT) }
}
enum class TacticalReason {
    UNSPECIFIED, GRENADE_DANGER, PLAYER_RETREAT_OR_BARRAGE, LAST_CONTACT_HIGHER, UNSEEN_INCOMING_FIRE,
    LOST_CONTACT, NARROW_ROUTE, QUIET_DEFENCE, NO_CONTACT, LOW_STRENGTH, MULTIPLE_FRONTS,
    AIR_CONTACT_WITHOUT_ROCKETS, ARMOURED_CONTACT, CLOSE_OR_APPROACHING_ENEMY, TOO_FEW_FIGHTERS,
    ENEMY_BELOW, DEFENSIVE_CONTACT, ENEMY_ABOVE, BLOCKED_SIGHT, ENEMY_WITHDRAWING,
    SPREAD_ENEMY_FRONT, SMALL_COMPACT_ENEMY, COMPACT_ENEMY, NORMAL_ADVANCE, PATTERN_COOLDOWN
}
enum class TacticalFailure { NONE, UNSPECIFIED, COVER_NOT_READY_TIMEOUT, COVER_LOST, MOVEMENT_TIMEOUT }

data class TacticalMember(
    val id: UUID, val position: Vec3, val role: NpcClass = NpcClass.RIFLEMAN,
    val health: Double = 1.0, val ready: Boolean = true, val canFire: Boolean = false,
    val suppressed: Boolean = false, val rockets: Boolean = false, val firingAt: Vec3? = null,
    val recentFire: Boolean = canFire, val recentFireAt: Vec3? = firingAt
)
data class TacticalContact(
    val id: UUID, val position: Vec3, val seenAt: Long, val velocity: Vec3 = Vec3.ZERO,
    val armoured: Boolean = false, val priority: Double = 1.0, val airborne: Boolean = false
)
data class TacticalSnapshot(
    val now: Long, val order: SquadOrder, val stamp: Int, val center: Vec3, val home: Vec3?,
    val members: List<TacticalMember>, val contacts: List<TacticalContact>,
    val incoming: List<Vec3> = emptyList(), val grenade: Boolean = false,
    val narrow: Boolean = false, val open: Boolean = false, val stalled: Boolean = false,
    val peakStrength: Int = members.size
) {
    val visible get() = contacts.filter { now - it.seenAt < 40 }
    val fighting get() = members.filter { it.ready && it.health >= 0.3 }
    val offensive get() = order == SquadOrder.ATTACK
}
data class TacticalChoice(
    val pattern: TacticalPattern, val focus: Vec3?, val emergency: Boolean = false,
    val reason: TacticalReason = TacticalReason.UNSPECIFIED
)
