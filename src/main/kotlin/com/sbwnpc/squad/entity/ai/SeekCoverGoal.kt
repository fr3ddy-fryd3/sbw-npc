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
 * MVP suppression response: duck behind terrain and hold, no peek-and-return-fire yet (a possible
 * v2 if this feels too passive in practice). Active only while [NpcEntity.isSuppressed] and a
 * [NpcEntity.threatPos] is known. NpcGunAttackGoal/GrenadeThrowGoal both refuse to run while
 * suppressed, so this goal has the mob to itself for movement during that window — no flag
 * contention to worry about there. Melee self-defense (goalSelector priority 2, this goal is 3)
 * still preempts it normally: an enemy in your face gets fought, not fled from.
 */
class SeekCoverGoal(private val mob: NpcEntity) : Goal() {

    private var coverTarget: BlockPos? = null
    private var inCover = false
    private var nextEvaluateTick = 0

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    override fun canUse(): Boolean = mob.isSuppressed() && mob.threatPos != null
    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        coverTarget = null
        inCover = false
        nextEvaluateTick = 0
    }

    override fun stop() {
        coverTarget = null
        inCover = false
        mob.navigation.stop()
    }

    override fun tick() {
        val threat = mob.threatPos ?: return

        coverTarget?.let { target ->
            if (mob.position().closerThan(target.center, 1.5)) {
                inCover = true
                mob.navigation.stop()
                return
            }
            if (!inCover) return // still travelling to it, let this leg finish
        }
        if (inCover) return // arrived — just hold until canUse() flips false

        if (mob.tickCount < nextEvaluateTick) return
        nextEvaluateTick = mob.tickCount + REEVALUATE_INTERVAL

        val level = mob.level() as? ServerLevel ?: return
        val candidate = findCover(level, threat) ?: fallbackAwayFrom(threat)
        if (candidate != null) {
            coverTarget = candidate
            mob.navigation.moveTo(candidate.x + 0.5, candidate.y.toDouble(), candidate.z + 0.5, 1.0)
        }
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
        private const val REEVALUATE_INTERVAL = 10
        private const val FALLBACK_DISTANCE = 3.0
    }
}
