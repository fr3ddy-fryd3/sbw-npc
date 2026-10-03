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
enum class TacticalJob { COVER, ADVANCE, FLANK, OBSERVE, SEARCH, REGROUP, RETREAT, ANTI_ARMOUR, RESERVE, WAIT }

data class TacticalMember(
    val id: UUID, val position: Vec3, val role: NpcClass = NpcClass.RIFLEMAN,
    val health: Double = 1.0, val ready: Boolean = true, val canFire: Boolean = false,
    val suppressed: Boolean = false, val rockets: Boolean = false, val firingAt: Vec3? = null
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
    val visible get() = contacts.filter { now - it.seenAt <= 20 }
    val fighting get() = members.filter { it.ready && it.health >= 0.3 }
    val offensive get() = order == SquadOrder.ATTACK
}
data class TacticalChoice(val pattern: TacticalPattern, val focus: Vec3?, val emergency: Boolean = false)

/** Decisions use member observations. There is deliberately no world/entity lookup here. */
object TacticalRules {
    fun choose(view: TacticalSnapshot): TacticalChoice {
        val contacts = view.visible
        var focus = contacts.maxByOrNull { it.priority / (1.0 + it.position.distanceTo(view.center) / 40.0) }?.position
            ?: view.contacts.maxByOrNull { it.seenAt }?.position
        if (view.grenade) return TacticalChoice(TacticalPattern.EVADE, focus, true)
        if (view.order == SquadOrder.RETREAT || view.order == SquadOrder.BARRAGE)
            return TacticalChoice(TacticalPattern.FOLLOW_ORDER, null, true)
        if (contacts.isEmpty()) {
            if (view.incoming.isNotEmpty()) return TacticalChoice(TacticalPattern.RETURN_FIRE, view.incoming.first())
            if (view.contacts.isNotEmpty() && view.offensive) return TacticalChoice(TacticalPattern.SEARCH, focus)
            if (view.narrow && view.order != SquadOrder.DEFEND) return TacticalChoice(TacticalPattern.FILE, view.home)
            return TacticalChoice(if (view.order == SquadOrder.DEFEND) TacticalPattern.CONSOLIDATE else TacticalPattern.FOLLOW_ORDER, null)
        }
        if (view.fighting.size < maxOf(1, view.peakStrength / 2))
            return TacticalChoice(TacticalPattern.REORGANIZE, focus, true)
        val directions = (contacts.map { it.position } + view.incoming).map {
            it.subtract(view.center).multiply(1.0,0.0,1.0).normalize()
        }.filter { it.lengthSqr() > 0.5 }
        val multipleFronts = directions.indices.any { a -> directions.indices.any { b -> a < b && directions[a].dot(directions[b]) < -0.2 } }
        if (multipleFronts) return TacticalChoice(
            if (view.members.count { it.suppressed } * 2 >= view.members.size) TacticalPattern.BREAK_CONTACT else TacticalPattern.REORIENT,
            focus, true)
        val armour = contacts.firstOrNull { it.armoured || it.airborne }
        if (armour?.airborne == true && view.fighting.none { it.rockets })
            return TacticalChoice(TacticalPattern.REORIENT,armour.position,true)
        if (armour != null) return TacticalChoice(
            if (view.fighting.any { it.rockets }) TacticalPattern.ANTI_ARMOUR else TacticalPattern.AVOID_ARMOUR,
            armour.position, true)
        if (contacts.any { contact ->
            val towardsUs = view.center.subtract(contact.position).normalize()
            contact.position.distanceTo(view.center) < 8.0 ||
                (contact.position.distanceTo(view.center) < 22.0 && contact.velocity.dot(towardsUs) > 0.08)
        }) return TacticalChoice(TacticalPattern.REPEL,focus,true)
        if (view.offensive && view.fighting.count { it.role != NpcClass.MEDIC } < 2)
            return TacticalChoice(TacticalPattern.FOLLOW_ORDER,focus)
        if (focus != null && view.center.y - focus.y >= 6.0) return TacticalChoice(TacticalPattern.HOLD_HEIGHT, focus)
        if (!view.offensive) return TacticalChoice(TacticalPattern.REORIENT, focus)
        if (focus != null && focus.y - view.center.y >= 6.0) return TacticalChoice(TacticalPattern.ATTACK_HEIGHT, focus)
        if (view.narrow) return TacticalChoice(TacticalPattern.FILE, focus)
        if (view.stalled) return TacticalChoice(TacticalPattern.DISLODGE, focus)
        if (contacts.all { it.velocity.dot(it.position.subtract(view.center).normalize()) > 0.08 })
            return TacticalChoice(TacticalPattern.PURSUE,focus)
        if (contacts.any { it.position.distanceTo(focus!!) > 24.0 }) {
            focus = contacts.minBy { candidate ->
                contacts.count { it.position.distanceTo(candidate.position) < 14.0 } +
                    candidate.position.distanceTo(view.center)*0.01 - candidate.priority*0.15
            }.position
            return TacticalChoice(TacticalPattern.FOCUS_SECTOR,focus)
        }
        if (contacts.size <= 2 && view.fighting.size >= 8 && contacts.all { it.position.distanceTo(focus!!) <= 14.0 })
            return TacticalChoice(TacticalPattern.ENCIRCLE,focus)
        if ((contacts.size >= 2 || contacts.any { it.priority >= 2.0 }) &&
            contacts.all { it.position.distanceTo(focus!!) <= 14.0 } && view.fighting.size >= 4)
            return TacticalChoice(TacticalPattern.FLANK, focus)
        return TacticalChoice(TacticalPattern.BOUND, focus)
    }

    fun withinOrder(view: TacticalSnapshot, candidate: Vec3, radius: Double): Boolean =
        view.order != SquadOrder.DEFEND || view.home == null || candidate.distanceTo(view.home) <= radius
}

class TacticalTask(val job: TacticalJob, val anchor: Vec3, val focus: Vec3?, val plan: Int) {
    var position: Vec3? = null
    var nextSearch = Long.MIN_VALUE
    var failures = 0
    var lastProgress = 0L
    var closest = Double.MAX_VALUE
}
class TacticalPlan(
    val id: Int, val pattern: TacticalPattern, val focus: Vec3?, val started: Long, val stamp: Int,
    var status: TacticalStatus = TacticalStatus.PREPARING
) {
    val tasks = LinkedHashMap<UUID, TacticalTask>()
    var movingHalf = 0
    var phaseSince = started
    var bounds = 0
    var lastCover = started
    var origin: Vec3? = null
    var flankSide = 0.0
    val passed = HashSet<UUID>()
}

/** Runtime state belongs to a Squad. A world reload starts a fresh tactical assessment. */
class SquadTacticalState {
    val contacts = LinkedHashMap<UUID, TacticalContact>()
    var snapshot: TacticalSnapshot? = null
    var plan: TacticalPlan? = null
        private set
    var nextAssessment = Long.MIN_VALUE
    var peakStrength = 0
    var blockedUntil = Long.MIN_VALUE
    var blockedPattern: TacticalPattern? = null
    val blocked = HashMap<TacticalPattern,Long>()
    var flankSide = 0.0
    private var serial = 0

    fun select(choice: TacticalChoice, stamp: Int, now: Long): Boolean {
        val old = plan
        val changedOrder = old != null && old.stamp != stamp
        val expired = old == null || old.status == TacticalStatus.FAILED || old.status == TacticalStatus.COMPLETED || (old.pattern == TacticalPattern.EVADE && choice.pattern != TacticalPattern.EVADE) || now - old.started >= if (old.pattern == TacticalPattern.ENCIRCLE) 480 else 240
        val changedFocus = old?.focus != null && choice.focus != null && old.focus.distanceTo(choice.focus) > 20.0
        val ready = old == null || old.pattern in setOf(TacticalPattern.FOLLOW_ORDER,TacticalPattern.CONSOLIDATE,
            TacticalPattern.SEARCH,TacticalPattern.RETURN_FIRE) || now - old.started >= 80
        if (!changedOrder && !expired && !choice.emergency && (!ready || (old?.pattern == choice.pattern && !changedFocus))) return false
        if (!changedOrder && !expired && choice.emergency && old?.pattern == choice.pattern && !changedFocus) return false
        if (changedOrder) { blockedPattern = null; blocked.clear() }
        plan = TacticalPlan(++serial, choice.pattern, choice.focus, now, stamp).also {
            it.flankSide = flankSide
            if (old?.pattern == choice.pattern && choice.pattern in setOf(TacticalPattern.PURSUE,TacticalPattern.REORGANIZE) && !changedOrder) it.origin = old.origin
        }
        return true
    }

    fun fail(now: Long) {
        plan?.let {
            blockedPattern = it.pattern
            blocked[it.pattern] = now + 120
            if (it.pattern in setOf(TacticalPattern.FLANK,TacticalPattern.DISLODGE,TacticalPattern.ATTACK_HEIGHT))
                flankSide = if (it.flankSide == 0.0) -1.0 else -it.flankSide
            it.status = TacticalStatus.FAILED
        }
        blockedUntil = now + 120
        nextAssessment = now
    }
}
