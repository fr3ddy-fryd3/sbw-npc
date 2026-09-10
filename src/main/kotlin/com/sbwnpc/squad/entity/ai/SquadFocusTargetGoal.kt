package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/**
 * Target selector driven by the squad's focus entity:
 *  - ATTACK: hunt the focus directly.
 *  - DEFEND: attack whoever last hurt the guarded focus (if that attacker is hostile to us).
 * Runs at higher priority than nearest-enemy so a commanded target wins.
 */
class SquadFocusTargetGoal(private val mob: NpcEntity) : Goal() {

    private var desired: LivingEntity? = null

    init {
        setFlags(EnumSet.of(Flag.TARGET))
    }

    private fun computeDesired(): LivingEntity? {
        val squad = mob.currentSquad() ?: return null
        val fid = squad.focusEntity ?: return null
        val level = mob.level() as? ServerLevel ?: return null
        val focus = level.getEntity(fid) as? LivingEntity ?: return null
        if (!focus.isAlive) return null
        return when (squad.order) {
            SquadOrder.ATTACK -> focus
            SquadOrder.DEFEND -> focus.lastHurtByMob?.takeIf {
                it.isAlive && it !== mob && SquadTeams.isHostile(mob, it)
            }
            else -> null
        }
    }

    override fun canUse(): Boolean {
        desired = computeDesired()
        return desired != null && desired !== mob
    }

    override fun canContinueToUse(): Boolean {
        val d = mob.target ?: return false
        if (!d.isAlive) return false
        return d === computeDesired()
    }

    override fun start() {
        mob.target = desired
    }

    override fun stop() {
        desired = null
        if (mob.target?.isAlive != true) mob.target = null
    }
}
