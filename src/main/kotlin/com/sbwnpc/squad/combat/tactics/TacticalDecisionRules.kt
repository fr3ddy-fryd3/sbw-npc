package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder

/** A null result lets the next rule run; a choice ends the assessment. See TacticalRules for priority. */
internal object TacticalDecisionRules {
    fun followAttackOperation(situation: TacticalSituation): TacticalChoice? {
        if (!situation.view.offensive || situation.view.attackAssignment == null) return null
        return TacticalChoice(TacticalPattern.COORDINATED_ATTACK, situation.focus, reason = TacticalReason.OPERATION_ASSIGNED)
    }
    fun evadeGrenade(situation: TacticalSituation): TacticalChoice? {
        if (!situation.view.grenade) return null
        return TacticalChoice(TacticalPattern.EVADE, situation.focus, emergency = true, reason = TacticalReason.GRENADE_DANGER)
    }

    fun respectExplicitOrder(situation: TacticalSituation): TacticalChoice? {
        if (situation.view.order !in setOf(SquadOrder.RETREAT, SquadOrder.BARRAGE)) return null
        return TacticalChoice(TacticalPattern.FOLLOW_ORDER, null, emergency = true, reason = TacticalReason.PLAYER_RETREAT_OR_BARRAGE)
    }

    fun handleNoVisibleContacts(situation: TacticalSituation): TacticalChoice? {
        if (situation.visible.isNotEmpty()) return null
        val view = situation.view
        if (view.regrouping == true) {
            return TacticalChoice(TacticalPattern.REORGANIZE, situation.focus, emergency = true, reason = TacticalReason.LOW_STRENGTH)
        }
        val rememberedEnemy = view.contacts.isNotEmpty()
        // A ridge may conceal the last contact while the squad is climbing towards it.
        if (view.offensive && rememberedEnemy && situation.enemyHasHeightAdvantage) {
            return TacticalChoice(TacticalPattern.ATTACK_HEIGHT, situation.focus, reason = TacticalReason.LAST_CONTACT_HIGHER)
        }
        if (view.incoming.isNotEmpty()) {
            return TacticalChoice(TacticalPattern.RETURN_FIRE, view.incoming.first(), reason = TacticalReason.UNSEEN_INCOMING_FIRE)
        }
        followAttackOperation(situation)?.let { return it }
        if (rememberedEnemy && view.offensive) {
            return TacticalChoice(TacticalPattern.SEARCH, situation.focus, reason = TacticalReason.LOST_CONTACT)
        }
        if (view.narrow && !DefensiveOverwatch.holdsArea(view)) {
            return TacticalChoice(TacticalPattern.FILE, view.home, reason = TacticalReason.NARROW_ROUTE)
        }
        return if (view.order == SquadOrder.DEFEND) {
            TacticalChoice(TacticalPattern.CONSOLIDATE, null, reason = TacticalReason.QUIET_DEFENCE)
        } else {
            TacticalChoice(TacticalPattern.FOLLOW_ORDER, null, reason = TacticalReason.NO_CONTACT)
        }
    }

    fun reorganizeDepletedSquad(situation: TacticalSituation): TacticalChoice? {
        if (!(situation.view.regrouping ?: situation.lostHalfStrength)) return null
        return TacticalChoice(TacticalPattern.REORGANIZE, situation.focus, emergency = true, reason = TacticalReason.LOW_STRENGTH)
    }

    fun respondToMultipleFronts(situation: TacticalSituation): TacticalChoice? {
        if (!situation.attackedFromMultipleFronts()) return null
        val pattern = if (situation.majoritySuppressed) TacticalPattern.BREAK_CONTACT else TacticalPattern.REORIENT
        return TacticalChoice(pattern, situation.focus, emergency = true, reason = TacticalReason.MULTIPLE_FRONTS)
    }

    fun respondToArmour(situation: TacticalSituation): TacticalChoice? {
        val contact = situation.firstArmouredContact ?: return null
        if (contact.airborne && !situation.hasRockets) {
            return TacticalChoice(TacticalPattern.REORIENT, contact.position, emergency = true, reason = TacticalReason.AIR_CONTACT_WITHOUT_ROCKETS)
        }
        val pattern = if (situation.hasRockets) TacticalPattern.ANTI_ARMOUR else TacticalPattern.AVOID_ARMOUR
        return TacticalChoice(pattern, contact.position, emergency = true, reason = TacticalReason.ARMOURED_CONTACT)
    }

    fun repelCloseEnemy(situation: TacticalSituation): TacticalChoice? {
        if (!situation.hasCloseOrApproachingEnemy()) return null
        return TacticalChoice(TacticalPattern.REPEL, situation.focus, emergency = true, reason = TacticalReason.CLOSE_OR_APPROACHING_ENEMY)
    }

    fun holdWithTooFewFighters(situation: TacticalSituation): TacticalChoice? {
        if (!situation.view.offensive || !situation.hasTooFewAttackers) return null
        return TacticalChoice(TacticalPattern.FOLLOW_ORDER, situation.focus, reason = TacticalReason.TOO_FEW_FIGHTERS)
    }

    fun holdHigherGround(situation: TacticalSituation): TacticalChoice? {
        if (!situation.hasHeightAdvantage) return null
        return TacticalChoice(TacticalPattern.HOLD_HEIGHT, situation.focus, reason = TacticalReason.ENEMY_BELOW)
    }

    fun holdDefensiveOrder(situation: TacticalSituation): TacticalChoice? {
        if (situation.view.offensive) return null
        return TacticalChoice(TacticalPattern.REORIENT, situation.focus, reason = TacticalReason.DEFENSIVE_CONTACT)
    }

    fun attackHigherGround(situation: TacticalSituation): TacticalChoice? {
        if (!situation.enemyHasHeightAdvantage) return null
        return TacticalChoice(TacticalPattern.ATTACK_HEIGHT, situation.focus, reason = TacticalReason.ENEMY_ABOVE)
    }

    fun crossNarrowRoute(situation: TacticalSituation): TacticalChoice? {
        if (!situation.view.narrow) return null
        return TacticalChoice(TacticalPattern.FILE, situation.focus, reason = TacticalReason.NARROW_ROUTE)
    }

    fun dislodgeBlockedEnemy(situation: TacticalSituation): TacticalChoice? {
        if (!situation.view.stalled) return null
        return TacticalChoice(TacticalPattern.DISLODGE, situation.focus, reason = TacticalReason.BLOCKED_SIGHT)
    }

    fun pursueWithdrawingEnemy(situation: TacticalSituation): TacticalChoice? {
        if (!situation.enemiesWithdrawing()) return null
        return TacticalChoice(TacticalPattern.PURSUE, situation.focus, reason = TacticalReason.ENEMY_WITHDRAWING)
    }

    fun focusSpreadEnemy(situation: TacticalSituation): TacticalChoice? {
        if (!situation.enemyFrontIsSpread()) return null
        return TacticalChoice(TacticalPattern.FOCUS_SECTOR, situation.isolatedSector(), reason = TacticalReason.SPREAD_ENEMY_FRONT)
    }

    fun encircleSmallEnemy(situation: TacticalSituation): TacticalChoice? {
        if (situation.visible.size > 2 || situation.fighters.size < 8 || !situation.enemyIsCompact()) return null
        return TacticalChoice(TacticalPattern.ENCIRCLE, situation.focus, reason = TacticalReason.SMALL_COMPACT_ENEMY)
    }

    fun flankCompactEnemy(situation: TacticalSituation): TacticalChoice? {
        val warrantsFlank = situation.visible.size >= 2 || situation.visible.any { it.priority >= 2.0 }
        if (!warrantsFlank || !situation.enemyIsCompact() || situation.fighters.size < 4) return null
        return TacticalChoice(TacticalPattern.FLANK, situation.focus, reason = TacticalReason.COMPACT_ENEMY)
    }
}
