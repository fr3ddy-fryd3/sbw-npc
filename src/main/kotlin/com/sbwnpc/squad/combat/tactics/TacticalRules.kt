package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3

/** Decisions use member observations. There is deliberately no world/entity lookup here. */
object TacticalRules : TacticalDecisionPolicy {
    override fun choose(view: TacticalSnapshot): TacticalChoice {
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
