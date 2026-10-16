package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3

/** First matching rule wins. Rules read observations and never query or modify the world. */
object TacticalRules : TacticalDecisionPolicy {
    private val priorityOrder: List<(TacticalSituation) -> TacticalChoice?> = listOf(
        TacticalDecisionRules::evadeGrenade,
        TacticalDecisionRules::respectExplicitOrder,
        TacticalDecisionRules::handleNoVisibleContacts,
        TacticalDecisionRules::reorganizeDepletedSquad,
        TacticalDecisionRules::respondToMultipleFronts,
        TacticalDecisionRules::respondToArmour,
        TacticalDecisionRules::repelCloseEnemy,
        TacticalDecisionRules::holdWithTooFewFighters,
        TacticalDecisionRules::followAttackOperation,
        TacticalDecisionRules::holdHigherGround,
        TacticalDecisionRules::holdDefensiveOrder,
        TacticalDecisionRules::attackHigherGround,
        TacticalDecisionRules::crossNarrowRoute,
        TacticalDecisionRules::dislodgeBlockedEnemy,
        TacticalDecisionRules::pursueWithdrawingEnemy,
        TacticalDecisionRules::focusSpreadEnemy,
        TacticalDecisionRules::encircleSmallEnemy,
        TacticalDecisionRules::flankCompactEnemy,
    )

    override fun choose(view: TacticalSnapshot): TacticalChoice = chooseAvailable(view, emptySet())!!

    override fun alternative(view: TacticalSnapshot, blocked: Set<TacticalPattern>): TacticalChoice? = chooseAvailable(view, blocked)

    private fun chooseAvailable(view: TacticalSnapshot, blocked: Set<TacticalPattern>): TacticalChoice? {
        val situation = TacticalSituation(view)
        for (rule in priorityOrder) {
            val choice = rule(situation)
            if (choice != null && choice.pattern !in blocked) return choice
        }
        if (TacticalPattern.BOUND in blocked) return null
        return TacticalChoice(TacticalPattern.BOUND, situation.focus, reason = TacticalReason.NORMAL_ADVANCE)
    }

    fun withinOrder(view: TacticalSnapshot, candidate: Vec3, radius: Double): Boolean = view.order != SquadOrder.DEFEND || view.home == null || candidate.distanceTo(view.home) <= radius

    fun stalled(blocked: Int, engaged: Int): Boolean = engaged >= 2 && blocked >= maxOf(2, (engaged + 1) / 2)
}
