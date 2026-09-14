package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal

/**
 * Same fix as [IdleLookAroundGoal], for the same underlying reason: vanilla
 * `WaterAvoidingRandomStrollGoal`/`RandomStrollGoal.canUse()` has NO awareness of the mob's combat
 * state at all (confirmed via the real vanilla source, not guessed) — it can trigger at any time,
 * including mid-combat or while dug in, and would walk the mob off toward a random nearby point.
 * SmartBrainLib Behaviours don't participate in `GoalSelector`'s `Flag` system, so nothing here
 * automatically excludes it the way a real target/flag conflict would in a fully vanilla AI.
 *
 * `target == null` alone was already the right gate for ordinary combat (SeekCoverBehaviour/
 * GunAttackBehaviour only ever run with a live target or while suppressed with COVER_HOLD set).
 * `!diggedIn` is required in addition: a dug-in mob deliberately clears COVER_HOLD for its whole
 * holding duration (see `SeekCoverBehaviour.enterDugInHolding`) so `GunAttackBehaviour` can still
 * fire from the hole, and its target can legitimately go null for a moment (dies, breaks LOS)
 * without the mob actually being done holding its position — reported in-game as digging in not
 * reliably preventing the mob from wandering off once its immediate target disappeared.
 */
class IdleWanderGoal(private val npc: NpcEntity, speedModifier: Double) :
    WaterAvoidingRandomStrollGoal(npc, speedModifier) {
    private fun eligible() = npc.target == null && !npc.diggedIn && !npc.vehicleTransport
    override fun canUse(): Boolean = eligible() && super.canUse()
    override fun canContinueToUse(): Boolean = eligible() && super.canContinueToUse()
}
