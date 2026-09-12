package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * Full suppression response — not just duck-and-hold: while suppressed, the mob finds a point the
 * threat's last known position can't see, ducks there, then periodically steps back OUT to return
 * fire on its current target before ducking back in, repeating for as long as it stays suppressed.
 *
 * Drives [NpcEntity.coverPhase]. During [NpcEntity.CoverPhase.PEEKING],
 * [NpcEntity.combatLockedByCover] is false, so `GunAttackBehaviour`/`GrenadeThrowGoal` take back over
 * movement/aim/fire for that window — no goal-flag conflict, since neither of those goals reserves
 * any [Flag] at all (confirmed against this codebase's actual goal wiring, not assumed). This goal
 * itself just steps the mob out toward its target for the peek and otherwise gets out of the way;
 * it does not fight for control the way the old duck-and-hold-only version implicitly did by never
 * yielding at all.
 */
class SeekCoverGoal(private val mob: NpcEntity) : Goal() {

    private var coverTarget: BlockPos? = null
    private var phaseUntilTick = 0

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    override fun canUse(): Boolean = mob.isSuppressed() && mob.threatPos != null
    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        coverTarget = null
        phaseUntilTick = 0
        mob.coverPhase = NpcEntity.CoverPhase.MOVING_TO_COVER
    }

    override fun stop() {
        coverTarget = null
        mob.coverPhase = NpcEntity.CoverPhase.NONE
        mob.navigation.stop()
    }

    override fun tick() {
        val threat = mob.threatPos ?: return
        val level = mob.level() as? ServerLevel ?: return

        when (mob.coverPhase) {
            NpcEntity.CoverPhase.NONE -> mob.coverPhase = NpcEntity.CoverPhase.MOVING_TO_COVER
            NpcEntity.CoverPhase.MOVING_TO_COVER -> tickMovingToCover(level, threat)
            NpcEntity.CoverPhase.IN_COVER -> tickInCover()
            NpcEntity.CoverPhase.PEEKING -> tickPeeking()
            NpcEntity.CoverPhase.RETURNING_TO_COVER -> tickReturningToCover()
        }
    }

    private fun tickMovingToCover(level: ServerLevel, threat: Vec3) {
        val target = coverTarget
        if (target != null) {
            if (mob.position().closerThan(target.center, 1.5)) enterCover()
            return // still travelling this leg either way
        }
        val candidate = findCover(level, threat) ?: fallbackAwayFrom(threat)
        if (candidate != null) {
            coverTarget = candidate
            mob.navigation.moveTo(candidate.x + 0.5, candidate.y.toDouble(), candidate.z + 0.5, 1.0)
        }
    }

    private fun enterCover() {
        mob.navigation.stop()
        mob.coverPhase = NpcEntity.CoverPhase.IN_COVER
        phaseUntilTick = mob.tickCount + DWELL_TICKS + mob.random.nextInt(DWELL_JITTER)
    }

    private fun tickInCover() {
        if (mob.tickCount < phaseUntilTick) return
        val target = mob.target
        if (target != null && target.isAlive) {
            mob.coverPhase = NpcEntity.CoverPhase.PEEKING
            phaseUntilTick = mob.tickCount + PEEK_TICKS
            // Step toward the target — GunAttackBehaviour (unlocked now, see combatLockedByCover)
            // takes over aiming/approach/fire from here; this is just enough of a nudge to clear
            // whatever's currently blocking sight from the cover point itself.
            mob.navigation.moveTo(target.x, target.y, target.z, 1.0)
        } else {
            // Nothing to shoot at yet — stay down, recheck shortly rather than popping out blind.
            phaseUntilTick = mob.tickCount + RECHECK_TICKS
        }
    }

    private fun tickPeeking() {
        val target = mob.target
        if (mob.tickCount >= phaseUntilTick || target == null || !target.isAlive) {
            duckBackToCover()
        }
    }

    private fun tickReturningToCover() {
        val target = coverTarget
        if (target == null) {
            mob.coverPhase = NpcEntity.CoverPhase.MOVING_TO_COVER
            return
        }
        if (mob.position().closerThan(target.center, 1.5)) {
            enterCover()
            return
        }
        mob.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }

    private fun duckBackToCover() {
        mob.coverPhase = NpcEntity.CoverPhase.RETURNING_TO_COVER
        val target = coverTarget ?: return
        mob.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }

    /** Samples points around the mob and keeps the nearest one the threat's last known position
     *  can't actually see (blocked by terrain), rather than just "anywhere N blocks away". */
    private fun findCover(level: ServerLevel, threat: Vec3): BlockPos? {
        val origin = mob.blockPosition()
        val candidates = (1..SAMPLE_COUNT).map {
            val angle = mob.random.nextDouble() * Math.PI * 2
            val dist = MIN_RADIUS + mob.random.nextDouble() * (MAX_RADIUS - MIN_RADIUS)
            groundAt(level, origin.offset(Math.round(Math.cos(angle) * dist).toInt(), 0, Math.round(Math.sin(angle) * dist).toInt()))
        }
        return candidates.filter { isHiddenFrom(level, threat, it) }.minByOrNull { it.distSqr(origin) }
    }

    private fun isHiddenFrom(level: ServerLevel, threat: Vec3, candidate: BlockPos): Boolean {
        val from = threat.add(0.0, 1.5, 0.0)
        val to = Vec3(candidate.x + 0.5, candidate.y + 1.5, candidate.z + 0.5)
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob))
        return hit.type == HitResult.Type.BLOCK
    }

    /** Snaps to standable ground near [pos] — same heuristic shape as the groundAt() helpers used
     *  elsewhere in this codebase (ModNetwork/SquadToolItem), just bounded much tighter since this
     *  is only ever a few blocks from the mob's own feet. */
    private fun groundAt(level: ServerLevel, pos: BlockPos): BlockPos {
        var p = pos
        var guard = 0
        while (level.getBlockState(p).isAir && p.y > level.minBuildHeight && guard++ < 10) p = p.below()
        while (!level.getBlockState(p).isAir && guard++ < 10) p = p.above()
        return p
    }

    /** No reachable cover found — just put a few blocks between the mob and the threat instead of
     *  standing still under fire. */
    private fun fallbackAwayFrom(threat: Vec3): BlockPos? {
        val away = mob.position().subtract(threat)
        if (away.lengthSqr() < 1.0e-6) return null
        val dir = away.normalize()
        return BlockPos.containing(mob.position().add(dir.x * FALLBACK_DISTANCE, 0.0, dir.z * FALLBACK_DISTANCE))
    }

    companion object {
        private const val SAMPLE_COUNT = 12
        private const val MIN_RADIUS = 4.0
        private const val MAX_RADIUS = 8.0
        private const val FALLBACK_DISTANCE = 3.0
        private const val DWELL_TICKS = 20        // ~1s minimum before the first peek
        private const val DWELL_JITTER = 30        // + up to ~1.5s random, so a squad doesn't peek in lockstep
        private const val PEEK_TICKS = 50          // ~2.5s exposed before ducking back, unless target dies/breaks LOS first
        private const val RECHECK_TICKS = 15       // no target yet — check again soon rather than popping out blind
    }
}
