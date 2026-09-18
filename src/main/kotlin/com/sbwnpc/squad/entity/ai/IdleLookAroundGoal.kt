package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal

/**
 * Root-cause fix for a long-standing reported bug: "NPCs sometimes look somewhere other than where
 * they shoot". Traced (via the real vanilla source, not guessed) to vanilla `RandomLookAroundGoal`,
 * which this codebase still registers unconditionally at low priority (see
 * `NpcEntity.registerGoals`'s own doc comment — every subsystem that touches combat state moved to
 * SmartBrainLib Behaviours, but this one vanilla goal was left as "safe, never touched custom
 * state"). It reserves `Goal.Flag.LOOK` and drives `Mob.getLookControl()` — which moves
 * `yHeadRot`/`yBodyRot`, i.e. the RENDERED head direction — completely independently of
 * `GunAttackBehaviour`'s own `entity.lookAt(target, 30f, 30f)` call, which sets `xRot`/`yRot`
 * directly (the BODY rotation SuperbWarfare's `GunItem.shoot()` actually fires along, via
 * `shooter.lookAngle` = `calculateViewVector(getXRot(), getYRot())` — confirmed in SBW's
 * `GunItem.kt`). Since no other goal in this mod's `GoalSelector` still reserves `Flag.LOOK`,
 * vanilla's ~2%-per-tick random head turn was free to hijack the visible head for 1-2s at ANY
 * time, including mid-firefight, while the body (and the actual shot) still faced the real
 * target — exactly the mismatch reported.
 *
 * Only changes eligibility (idle-flavor look-around only, never while there's a combat target);
 * the actual random-look logic itself is untouched vanilla behaviour.
 */
class IdleLookAroundGoal(private val npc: NpcEntity) : RandomLookAroundGoal(npc) {
    override fun canUse(): Boolean = npc.target == null && !npc.aimingAtDrone && super.canUse()
    override fun canContinueToUse(): Boolean = npc.target == null && !npc.aimingAtDrone && super.canContinueToUse()
}
