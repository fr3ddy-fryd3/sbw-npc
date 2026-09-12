package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/**
 * Response to an [com.sbwnpc.squad.combat.Alarm] (heard nearby gunfire, or an ally went down without
 * a resolvable killer) — NOT combat, just "go look". If a real target turns up along the way (the
 * normal target-selector goals get direct line of sight), `mob.target` becomes non-null and this
 * goal's [canUse] goes false on its own — higher-priority combat goals take it from there.
 */
class InvestigateGoal(private val mob: NpcEntity) : Goal() {

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    override fun canUse(): Boolean = mob.target == null && !mob.combatLockedByCover() && mob.isAlert()
    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        mob.alertPos?.let { mob.navigation.moveTo(it.x, it.y, it.z, 1.0) }
    }

    override fun tick() {
        val pos = mob.alertPos ?: return
        if (mob.position().closerThan(pos, ARRIVE_DISTANCE) || mob.navigation.isDone) {
            mob.clearAlert()
        }
    }

    companion object {
        private const val ARRIVE_DISTANCE = 3.0
    }
}
