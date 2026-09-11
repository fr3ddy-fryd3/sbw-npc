package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity
import com.atsuishio.superbwarfare.tools.RangeTool.calculateFiringSolution
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/**
 * Supplements the grenadier's M79 (handled by NpcGunAttackGoal) with an occasional thrown grenade
 * at medium range — mainly useful for flushing a target out of cover the launcher can't reach.
 * "Instant action" goal: throws once in start(), then immediately deactivates (canContinueToUse
 * always false) — the cooldown lives in nextThrowTick rather than needing per-tick ticking while
 * inactive.
 *
 * Throw speed/gravity are placeholder constants (SBW's default projectile gravity 0.05, a guessed
 * ~1.0 blocks/tick toss speed) — need visual tuning once seen in-game.
 */
class GrenadeThrowGoal(private val mob: NpcEntity) : Goal() {

    private var nextThrowTick = 0

    init {
        setFlags(EnumSet.noneOf(Flag::class.java))
    }

    override fun canUse(): Boolean {
        if (mob.npcClass != NpcClass.GRENADIER) return false
        if (mob.isSuppressed()) return false // SeekCoverGoal owns the mob until this lapses
        if (mob.level() !is ServerLevel) return false
        if (mob.tickCount < nextThrowTick) return false
        val target = mob.target ?: return false
        if (!target.isAlive) return false
        val dist = mob.distanceTo(target)
        if (dist !in MIN_RANGE..MAX_RANGE || !mob.sensing.hasLineOfSight(target)) return false
        // This goal has no MOVE flag (never sidesteps itself) — if an ally is in the way, just
        // skip the throw this cycle; the main NpcGunAttackGoal running alongside it owns
        // positioning and will already be trying to clear its own line of fire.
        return FriendlyFireGuard.hasClearLineOfFire(mob, target.boundingBox.center)
    }

    override fun canContinueToUse() = false

    override fun start() {
        val target = mob.target ?: return
        val level = mob.level() as? ServerLevel ?: return

        val launchPos = mob.eyePosition
        val targetPos = target.boundingBox.center
        val solution = calculateFiringSolution(launchPos, targetPos, target.deltaMovement, THROW_SPEED, GRAVITY)

        val grenade = HandGrenadeEntity(mob, level)
        grenade.deltaMovement = solution
        level.addFreshEntity(grenade)

        nextThrowTick = mob.tickCount + COOLDOWN_TICKS + mob.random.nextInt(COOLDOWN_JITTER)
    }

    companion object {
        private const val MIN_RANGE = 5.0
        private const val MAX_RANGE = 16.0
        private const val THROW_SPEED = 1.0
        private const val GRAVITY = 0.05
        private const val COOLDOWN_TICKS = 100
        private const val COOLDOWN_JITTER = 60
    }
}
