package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/**
 * The consumer half of faction-wide contact sharing (see [TeamAwareness] — the reporter half was
 * already wired into [NpcGunAttackGoal]). Without this goal, ordinary infantry never actually acted
 * on a squadmate's relayed sighting: [SquadFocusTargetGoal] only fires on a squad-order-commanded
 * focus, and [MortarOperatorGoal] was the only thing reading [TeamAwareness.relayedContacts] at all —
 * so "one spots, the rest find out a few seconds later" never happened for anyone except the mortar
 * crew, which is the opposite of what it's for (the mortar has no infantry of its own to spot with).
 *
 * Excludes mortar crew for the same reason [SquadFocusTargetGoal] does: forcing `mob.target` on them
 * here would trip `MortarOperatorGoal.canUse()`'s self-defense bailout the same way the original bug
 * did, just through a different door.
 */
class SquadAwarenessTargetGoal(private val mob: NpcEntity) : Goal() {

    private var desired: LivingEntity? = null

    init {
        setFlags(EnumSet.of(Flag.TARGET))
    }

    private fun computeDesired(): LivingEntity? {
        if (mob.npcClass == NpcClass.MORTAR_OPERATOR || mob.npcClass == NpcClass.MORTAR_LOADER) return null
        val faction = SquadTeams.factionOf(mob) ?: return null
        val level = mob.level() as? ServerLevel ?: return null
        val followRangeSqr = (mob.getAttribute(Attributes.FOLLOW_RANGE)?.value ?: 48.0).let { it * it }
        return TeamAwareness.relayedContacts(faction, mob.tickCount.toLong())
            .asSequence()
            .mapNotNull { level.getEntity(it) as? LivingEntity }
            .filter { it.isAlive && it !== mob && SquadTeams.isHostile(mob, it) && mob.distanceToSqr(it) <= followRangeSqr }
            .minByOrNull { mob.distanceToSqr(it) }
    }

    override fun canUse(): Boolean {
        desired = computeDesired()
        return desired != null
    }

    override fun canContinueToUse(): Boolean {
        val target = mob.target ?: return false
        if (!target.isAlive) return false
        // Stay on it either while it's still relayed, or once the mob can see it itself (at which
        // point NpcGunAttackGoal's own reporting/engagement takes over the "why" of holding target).
        val faction = SquadTeams.factionOf(mob) ?: return false
        if (TeamAwareness.relayedContacts(faction, mob.tickCount.toLong()).contains(target.uuid)) return true
        return mob.sensing.hasLineOfSight(target)
    }

    override fun start() {
        mob.target = desired
    }

    override fun stop() {
        desired = null
        if (mob.target?.isAlive != true) mob.target = null
    }
}
