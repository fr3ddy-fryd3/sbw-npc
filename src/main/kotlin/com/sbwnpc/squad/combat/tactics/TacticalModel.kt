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
enum class TacticalJob { COVER, ADVANCE, FLANK, OBSERVE, SEARCH, REGROUP, RETREAT, ANTI_ARMOUR, RESERVE, WAIT, OVERWATCH }
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

/** Decisions use member observations. There is deliberately no world/entity lookup here. */
object TacticalRules {
    fun choose(view: TacticalSnapshot): TacticalChoice {
        val contacts = view.visible
        var focus = contacts.maxByOrNull { it.priority / (1.0 + it.position.distanceTo(view.center) / 40.0) }?.position
            ?: view.contacts.maxByOrNull { it.seenAt }?.position
        if (view.grenade) return TacticalChoice(TacticalPattern.EVADE, focus, true, TacticalReason.GRENADE_DANGER)
        if (view.order == SquadOrder.RETREAT || view.order == SquadOrder.BARRAGE)
            return TacticalChoice(TacticalPattern.FOLLOW_ORDER, null, true, TacticalReason.PLAYER_RETREAT_OR_BARRAGE)
        if (contacts.isEmpty()) {
            // The ridge can hide the enemy while we climb. Keep approaching its last seen
            // position instead of leaving fourteen men behind and sending two searchers.
            if (view.offensive && view.contacts.isNotEmpty() && focus != null && focus.y-view.center.y >= 8.0)
                return TacticalChoice(TacticalPattern.ATTACK_HEIGHT,focus,reason=TacticalReason.LAST_CONTACT_HIGHER)
            if (view.incoming.isNotEmpty()) return TacticalChoice(TacticalPattern.RETURN_FIRE, view.incoming.first(),reason=TacticalReason.UNSEEN_INCOMING_FIRE)
            if (view.contacts.isNotEmpty() && view.offensive) return TacticalChoice(TacticalPattern.SEARCH, focus,reason=TacticalReason.LOST_CONTACT)
            if (view.narrow && view.order != SquadOrder.DEFEND) return TacticalChoice(TacticalPattern.FILE, view.home,reason=TacticalReason.NARROW_ROUTE)
            return TacticalChoice(if (view.order == SquadOrder.DEFEND) TacticalPattern.CONSOLIDATE else TacticalPattern.FOLLOW_ORDER, null,
                reason=if (view.order == SquadOrder.DEFEND) TacticalReason.QUIET_DEFENCE else TacticalReason.NO_CONTACT)
        }
        if (view.fighting.size < maxOf(1, view.peakStrength / 2))
            return TacticalChoice(TacticalPattern.REORGANIZE, focus, true, TacticalReason.LOW_STRENGTH)
        val directions = (contacts.map { it.position } + view.incoming).map {
            it.subtract(view.center).multiply(1.0,0.0,1.0).normalize()
        }.filter { it.lengthSqr() > 0.5 }
        val attackDirection=(view.home ?: focus ?: view.center).subtract(view.center).multiply(1.0,0.0,1.0).normalize()
        val allAhead=view.offensive && attackDirection.lengthSqr()>0.5 && directions.all { it.dot(attackDirection)>=-0.1 }
        val multipleFronts = !allAhead && directions.indices.any { a -> directions.indices.any { b -> a < b && directions[a].dot(directions[b]) < -0.2 } }
        if (multipleFronts) return TacticalChoice(
            if (view.members.count { it.suppressed } * 2 >= view.members.size) TacticalPattern.BREAK_CONTACT else TacticalPattern.REORIENT,
            focus, true, TacticalReason.MULTIPLE_FRONTS)
        val armour = contacts.firstOrNull { it.armoured || it.airborne }
        if (armour?.airborne == true && view.fighting.none { it.rockets })
            return TacticalChoice(TacticalPattern.REORIENT,armour.position,true,TacticalReason.AIR_CONTACT_WITHOUT_ROCKETS)
        if (armour != null) return TacticalChoice(
            if (view.fighting.any { it.rockets }) TacticalPattern.ANTI_ARMOUR else TacticalPattern.AVOID_ARMOUR,
            armour.position, true, TacticalReason.ARMOURED_CONTACT)
        if (contacts.any { contact ->
            val towardsUs = view.center.subtract(contact.position).normalize()
            contact.position.distanceTo(view.center) < 8.0 ||
                (contact.position.distanceTo(view.center) < 22.0 && contact.velocity.dot(towardsUs) > 0.08)
        }) return TacticalChoice(TacticalPattern.REPEL,focus,true,TacticalReason.CLOSE_OR_APPROACHING_ENEMY)
        if (view.offensive && view.fighting.count { it.role != NpcClass.MEDIC } < 2)
            return TacticalChoice(TacticalPattern.FOLLOW_ORDER,focus,reason=TacticalReason.TOO_FEW_FIGHTERS)
        if (focus != null && view.center.y - focus.y >= 8.0) return TacticalChoice(TacticalPattern.HOLD_HEIGHT, focus,reason=TacticalReason.ENEMY_BELOW)
        if (!view.offensive) return TacticalChoice(TacticalPattern.REORIENT, focus,reason=TacticalReason.DEFENSIVE_CONTACT)
        if (focus != null && focus.y - view.center.y >= 8.0) return TacticalChoice(TacticalPattern.ATTACK_HEIGHT, focus,reason=TacticalReason.ENEMY_ABOVE)
        if (view.narrow) return TacticalChoice(TacticalPattern.FILE, focus,reason=TacticalReason.NARROW_ROUTE)
        if (view.stalled) return TacticalChoice(TacticalPattern.DISLODGE, focus,reason=TacticalReason.BLOCKED_SIGHT)
        if (contacts.all { it.velocity.dot(it.position.subtract(view.center).normalize()) > 0.08 })
            return TacticalChoice(TacticalPattern.PURSUE,focus,reason=TacticalReason.ENEMY_WITHDRAWING)
        if (contacts.any { it.position.distanceTo(focus!!) > 24.0 }) {
            focus = contacts.minBy { candidate ->
                contacts.count { it.position.distanceTo(candidate.position) < 14.0 } +
                    candidate.position.distanceTo(view.center)*0.01 - candidate.priority*0.15
            }.position
            return TacticalChoice(TacticalPattern.FOCUS_SECTOR,focus,reason=TacticalReason.SPREAD_ENEMY_FRONT)
        }
        if (contacts.size <= 2 && view.fighting.size >= 8 && contacts.all { it.position.distanceTo(focus!!) <= 14.0 })
            return TacticalChoice(TacticalPattern.ENCIRCLE,focus,reason=TacticalReason.SMALL_COMPACT_ENEMY)
        if ((contacts.size >= 2 || contacts.any { it.priority >= 2.0 }) &&
            contacts.all { it.position.distanceTo(focus!!) <= 14.0 } && view.fighting.size >= 4)
            return TacticalChoice(TacticalPattern.FLANK, focus,reason=TacticalReason.COMPACT_ENEMY)
        return TacticalChoice(TacticalPattern.BOUND, focus,reason=TacticalReason.NORMAL_ADVANCE)
    }

    fun withinOrder(view: TacticalSnapshot, candidate: Vec3, radius: Double): Boolean =
        view.order != SquadOrder.DEFEND || view.home == null || candidate.distanceTo(view.home) <= radius

    fun stalled(blocked: Int,engaged: Int): Boolean = engaged>=2 && blocked>=maxOf(2,(engaged+1)/2)
}

class TacticalTask(val job: TacticalJob, val anchor: Vec3, var focus: Vec3?, val plan: Int) {
    var position: Vec3? = null
    var nextSearch = Long.MIN_VALUE
    var failures = 0
    var lastProgress = 0L
    var closest = Double.MAX_VALUE
    var search: TacticalPositionSearch? = null
    var nextValidation = Long.MIN_VALUE
    var staging: Vec3? = null
    var opensLane = false
    var pausedAt: Long? = null
}

/** Keeps progress across tick budgets, including candidates after an unreachable first choice. */
class TacticalPositionSearch(val origin: Vec3, val probes: List<Vec3>) {
    var probeIndex = 0
    val scored = LinkedHashMap<Vec3, Double>()
    val visited = HashSet<Vec3>()
    var destinations: List<Vec3>? = null
    var destinationIndex = 0
    val rejections = LinkedHashMap<String,Int>()
    fun reject(reason: String) { rejections[reason]=(rejections[reason] ?: 0)+1 }
}
class TacticalPlan(
    val id: Int, val pattern: TacticalPattern, val focus: Vec3?, val started: Long, val stamp: Int,
    var status: TacticalStatus = TacticalStatus.PREPARING
) {
    val tasks = LinkedHashMap<UUID, TacticalTask>()
    var reason = TacticalReason.UNSPECIFIED
    var failure = TacticalFailure.NONE
    var movingHalf = 0
    var phaseSince = started
    var bounds = 0
    var lastCover = started
    var origin: Vec3? = null
    var flankSide = 0.0
    val passed = HashSet<UUID>()
    val failedMembers = HashSet<UUID>()
    val heightSupport = HashSet<UUID>()
    val flankSupport = HashSet<UUID>()
    val flankOrder = ArrayList<UUID>()
    val flankArrived = HashSet<UUID>()
    var medicGuard: UUID? = null
    var heightFront: Vec3? = null
    val waitingPosts = HashMap<UUID,Vec3>()
    var openingLane = false
    var laneAttempts = 0
    val laneMembers = HashSet<UUID>()
    var pausedSince = Long.MIN_VALUE
}

/** Runtime state belongs to a Squad. A world reload starts a fresh tactical assessment. */
class SquadTacticalState {
    val contacts = LinkedHashMap<UUID, TacticalContact>()
    var snapshot: TacticalSnapshot? = null
    var plan: TacticalPlan? = null
        private set
    var nextAssessment = Long.MIN_VALUE
    var nextTrace = Long.MIN_VALUE
    val holdAfterFailure = HashMap<UUID,Long>()
    var peakStrength = 0
    var blockedUntil = Long.MIN_VALUE
    var blockedPattern: TacticalPattern? = null
    val blocked = HashMap<TacticalPattern,Long>()
    var flankSide = 0.0
    private var serial = 0

    fun select(choice: TacticalChoice, stamp: Int, now: Long): Boolean {
        val old = plan
        val changedOrder = old != null && old.stamp != stamp
        val expired = old == null || old.status == TacticalStatus.FAILED || old.status == TacticalStatus.COMPLETED || (old.pattern == TacticalPattern.EVADE && choice.pattern != TacticalPattern.EVADE) ||
            now-old.started>=1200 || now-old.phaseSince>=if (old.pattern in TacticalFlanks.PATTERNS+TacticalPattern.ATTACK_HEIGHT) 480 else 240
        val changedFocus = old?.focus != null && choice.focus != null && old.focus.distanceTo(choice.focus) > 20.0
        if (!changedOrder && !expired && old?.pattern==TacticalPattern.REORIENT && choice.pattern==old.pattern) return false
        // Losing sight for a moment must not replace a flank already walking round the ridge
        // with a two-man search, or make every mover return to its previous position.
        if (!changedOrder && !expired && !choice.emergency && old?.pattern in ACTIVE_MANEUVERS &&
            (choice.pattern in PASSIVE_CHOICES || choice.pattern in ACTIVE_MANEUVERS)) return false
        val ready = old == null || old.pattern in setOf(TacticalPattern.FOLLOW_ORDER,TacticalPattern.CONSOLIDATE,
            TacticalPattern.SEARCH,TacticalPattern.RETURN_FIRE) || now - old.started >= 80
        if (!changedOrder && !expired && !choice.emergency && (!ready || (old?.pattern == choice.pattern && !changedFocus))) return false
        if (!changedOrder && !expired && choice.emergency && old?.pattern == choice.pattern && !changedFocus) return false
        if (changedOrder) { blockedPattern = null; blocked.clear(); holdAfterFailure.clear() }
        plan = TacticalPlan(++serial, choice.pattern, choice.focus, now, stamp).also {
            it.reason = choice.reason
            it.flankSide = flankSide
            if (old?.pattern == choice.pattern && choice.pattern in setOf(TacticalPattern.PURSUE,TacticalPattern.REORGANIZE) && !changedOrder) it.origin = old.origin
        }
        return true
    }

    private val ACTIVE_MANEUVERS = setOf(TacticalPattern.FLANK,TacticalPattern.ENCIRCLE,TacticalPattern.DISLODGE,
        TacticalPattern.ATTACK_HEIGHT,TacticalPattern.BOUND,TacticalPattern.PURSUE,TacticalPattern.FOCUS_SECTOR)
    private val PASSIVE_CHOICES = setOf(TacticalPattern.FOLLOW_ORDER,TacticalPattern.SEARCH,TacticalPattern.RETURN_FIRE,
        TacticalPattern.REORIENT,TacticalPattern.CONSOLIDATE)

    fun holdsAfterFailure(member: UUID,stamp: Int,now: Long): Boolean {
        val current=plan ?: return false
        if (current.stamp!=stamp) return false
        // The previous plan's safety hold cannot block the task which recovers from it.
        // Unassigned members still stay out of a frontal rush.
        if (current.status!=TacticalStatus.FAILED && member in current.tasks) return false
        return now<(holdAfterFailure[member] ?: Long.MIN_VALUE) ||
            (current.pattern in TacticalFlanks.PATTERNS+TacticalPattern.ATTACK_HEIGHT &&
                member in current.failedMembers && snapshot?.visible?.isNotEmpty()==true)
    }

    fun fail(now: Long, reason: TacticalFailure = TacticalFailure.UNSPECIFIED) {
        plan?.let {
            blockedPattern = it.pattern
            blocked[it.pattern] = now + 120
            if (it.pattern in setOf(TacticalPattern.FLANK,TacticalPattern.DISLODGE,TacticalPattern.ATTACK_HEIGHT))
                flankSide = if (it.flankSide == 0.0) -1.0 else -it.flankSide
            it.status = TacticalStatus.FAILED
            it.failure = reason
            for (id in it.tasks.keys) holdAfterFailure[id]=now+120
        }
        blockedUntil = now + 120
        nextAssessment = now
    }
}
