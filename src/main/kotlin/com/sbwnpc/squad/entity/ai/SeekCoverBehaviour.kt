package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * SmartBrain migration step 6 — direct port of the old `SeekCoverGoal` onto `ExtendedBehaviour`,
 * placed in `NpcEntity.getCoreTasks()` (see there) rather than a separate `Activity`: like
 * `InteractWithDoor`, this must keep ticking on every phase transition with its own state intact,
 * including through the PEEKING window — a real (mutually-exclusive) SmartBrainLib `Activity` would
 * have `stop()`/`start()` this behaviour every time `COVER_HOLD` toggles off/on for the peek, losing
 * `coverTarget`/`phase` right when they need to survive it. CORE has no such exclusivity (same as
 * the old goal, which reserved `Flag.MOVE` but was never challenged for it — `GunAttackBehaviour`/
 * `GrenadeThrowBehaviour` never reserved any flag either), so this keeps ticking continuously the whole
 * time the mob is suppressed, exactly like before.
 *
 * Full suppression response — not just duck-and-hold: while suppressed, the mob finds a point the
 * threat's last known position can't see, ducks there, then periodically steps back OUT to return
 * fire on its current target before ducking back in, repeating for as long as it stays suppressed.
 *
 * Drives [ModMemories.COVER_HOLD] (replaces `NpcEntity.coverPhase`'s externally-visible half — see
 * that memory's own doc comment). While `COVER_HOLD` is absent (the PEEKING window),
 * `NpcEntity.combatLockedByCover()` is false, so `GunAttackBehaviour`/`GrenadeThrowBehaviour` take back
 * over movement/aim/fire for that window — no conflict, since neither of those reserves any
 * `Flag`/exclusivity of its own (confirmed against this codebase's actual wiring, not assumed). This
 * behaviour itself just steps the mob out toward its target for the peek and otherwise gets out of
 * the way; it does not fight for control the way the old duck-and-hold-only version implicitly did
 * by never yielding at all.
 */
class SeekCoverBehaviour : ExtendedBehaviour<NpcEntity>() {

    private enum class Phase { MOVING_TO_COVER, IN_COVER, PEEKING, RETURNING_TO_COVER }

    private var phase = Phase.MOVING_TO_COVER
    private var coverTarget: BlockPos? = null
    private var phaseUntilTick = 0

    companion object {
        private const val SAMPLE_COUNT = 20
        private const val MIN_RADIUS = 5.0
        private const val MAX_RADIUS = 14.0
        private const val WALL_SCAN_STEP = 2       // grid spacing (blocks) for the wall-adjacency scan
        private const val FALLBACK_DISTANCE = 6.0
        private const val FALLBACK_SPREAD_RADIANS = Math.PI / 3.0 // +/- 60 deg off dead-away-from-threat
        private const val DWELL_TICKS = 20        // ~1s minimum before the first peek
        private const val DWELL_JITTER = 30        // + up to ~1.5s random, so a squad doesn't peek in lockstep
        private const val PEEK_TICKS = 50          // ~2.5s exposed before ducking back, unless target dies/breaks LOS first
        private const val RECHECK_TICKS = 15       // no target yet — check again soon rather than popping out blind

        private val NEIGHBOR_OFFSETS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(ModMemories.SUPPRESSING_THREAT.get(), MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = entity.isSuppressed()
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = entity.isSuppressed()

    override fun start(entity: NpcEntity) {
        phase = Phase.MOVING_TO_COVER
        coverTarget = null
        phaseUntilTick = 0
        BrainUtils.setMemory(entity, ModMemories.COVER_HOLD.get(), true)
    }

    override fun stop(entity: NpcEntity) {
        coverTarget = null
        entity.navigation.stop()
        BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
    }

    override fun tick(entity: NpcEntity) {
        val threat = entity.threatPos ?: return
        val level = entity.level() as? ServerLevel ?: return

        when (phase) {
            Phase.MOVING_TO_COVER -> tickMovingToCover(entity, level, threat)
            Phase.IN_COVER -> tickInCover(entity)
            Phase.PEEKING -> tickPeeking(entity)
            Phase.RETURNING_TO_COVER -> tickReturningToCover(entity)
        }
    }

    private fun tickMovingToCover(entity: NpcEntity, level: ServerLevel, threat: Vec3) {
        val target = coverTarget
        if (target != null) {
            if (entity.position().closerThan(target.center, 1.5)) enterCover(entity)
            return // still travelling this leg either way
        }
        val candidate = findCover(entity, level, threat) ?: fallbackAwayFrom(entity, threat)
        if (candidate != null) {
            coverTarget = candidate
            entity.navigation.moveTo(candidate.x + 0.5, candidate.y.toDouble(), candidate.z + 0.5, 1.0)
        }
    }

    private fun enterCover(entity: NpcEntity) {
        entity.navigation.stop()
        phase = Phase.IN_COVER
        phaseUntilTick = entity.tickCount + DWELL_TICKS + entity.random.nextInt(DWELL_JITTER)
    }

    private fun tickInCover(entity: NpcEntity) {
        if (entity.tickCount < phaseUntilTick) return
        val target = entity.target
        if (target != null && target.isAlive) {
            phase = Phase.PEEKING
            phaseUntilTick = entity.tickCount + PEEK_TICKS
            // Unlock — GunAttackBehaviour (no longer locked out, see combatLockedByCover) takes
            // over aiming/approach/fire from here; this is just enough of a nudge to clear
            // whatever's currently blocking sight from the cover point itself.
            BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
            entity.navigation.moveTo(target.x, target.y, target.z, 1.0)
        } else {
            // Nothing to shoot at yet — stay down, recheck shortly rather than popping out blind.
            phaseUntilTick = entity.tickCount + RECHECK_TICKS
        }
    }

    private fun tickPeeking(entity: NpcEntity) {
        val target = entity.target
        if (entity.tickCount >= phaseUntilTick || target == null || !target.isAlive) {
            duckBackToCover(entity)
        }
    }

    private fun tickReturningToCover(entity: NpcEntity) {
        val target = coverTarget
        if (target == null) {
            phase = Phase.MOVING_TO_COVER
            return
        }
        if (entity.position().closerThan(target.center, 1.5)) {
            enterCover(entity)
            return
        }
        entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }

    private fun duckBackToCover(entity: NpcEntity) {
        phase = Phase.RETURNING_TO_COVER
        BrainUtils.setMemory(entity, ModMemories.COVER_HOLD.get(), true)
        val target = coverTarget ?: return
        entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }

    /** Two candidate sources, both filtered down to points the threat's last known position can't
     *  actually see (blocked by terrain) — the raycast in [isHiddenFrom] is the real gate, this is
     *  just about generating candidates likely to pass it:
     *   - a coarse grid scan biased toward spots with a solid block right next to them (an actual
     *     wall/rock/building corner) — genuine physical cover, not just "some open spot that
     *     happens to be hidden by a terrain bump".
     *   - random angle/distance samples (same as before) — still useful in open/rubble terrain
     *     where there's no single obvious wall to hug.
     *  Grid-scan-only would miss legitimate cover in uneven open terrain; random-only was what
     *  actually shipped before and, per user feedback in-game, mostly just resulted in NPCs backing
     *  straight away from the threat ([fallbackAwayFrom]) instead of finding real cover — because
     *  purely random points rarely land next to a wall by chance within a small sample. */
    private fun findCover(entity: NpcEntity, level: ServerLevel, threat: Vec3): BlockPos? {
        val origin = entity.blockPosition()
        val candidates = ArrayList<BlockPos>(64)

        var dx = -MAX_RADIUS.toInt()
        while (dx <= MAX_RADIUS.toInt()) {
            var dz = -MAX_RADIUS.toInt()
            while (dz <= MAX_RADIUS.toInt()) {
                val distSq = (dx * dx + dz * dz).toDouble()
                if (distSq in (MIN_RADIUS * MIN_RADIUS)..(MAX_RADIUS * MAX_RADIUS)) {
                    val ground = groundAt(level, origin.offset(dx, 0, dz))
                    if (hasAdjacentSolidWall(level, ground)) candidates += ground
                }
                dz += WALL_SCAN_STEP
            }
            dx += WALL_SCAN_STEP
        }

        repeat(SAMPLE_COUNT) {
            val angle = entity.random.nextDouble() * Math.PI * 2
            val dist = MIN_RADIUS + entity.random.nextDouble() * (MAX_RADIUS - MIN_RADIUS)
            candidates += groundAt(level, origin.offset(Math.round(Math.cos(angle) * dist).toInt(), 0, Math.round(Math.sin(angle) * dist).toInt()))
        }

        return candidates.filter { isHiddenFrom(level, entity, threat, it) }.minByOrNull { it.distSqr(origin) }
    }

    /** A solid block at body or head height on any of the 4 horizontal sides — cheap proxy for
     *  "there's a wall/corner here", the same "not air = solid enough" heuristic [groundAt] already
     *  uses. Doesn't need to know which side the threat is on: [isHiddenFrom]'s raycast is what
     *  actually decides whether this particular wall blocks THIS particular threat. */
    private fun hasAdjacentSolidWall(level: ServerLevel, pos: BlockPos): Boolean {
        return NEIGHBOR_OFFSETS.any { (nx, nz) ->
            val side = pos.offset(nx, 0, nz)
            !level.getBlockState(side).isAir || !level.getBlockState(side.above()).isAir
        }
    }

    private fun isHiddenFrom(level: ServerLevel, entity: NpcEntity, threat: Vec3, candidate: BlockPos): Boolean {
        val from = threat.add(0.0, 1.5, 0.0)
        val to = Vec3(candidate.x + 0.5, candidate.y + 1.5, candidate.z + 0.5)
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
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

    /** No reachable cover found — put real distance between the mob and the threat instead of
     *  standing still under fire, angled off dead-away by a random spread so several suppressed
     *  squadmates retreating at once don't all bunch up on the same line behind the threat (which
     *  would just recreate the "everyone stacks together" problem this whole feature exists to
     *  avoid, one step removed). */
    private fun fallbackAwayFrom(entity: NpcEntity, threat: Vec3): BlockPos? {
        val away = entity.position().subtract(threat)
        if (away.lengthSqr() < 1.0e-6) return null
        val baseAngle = Math.atan2(away.z, away.x)
        val angle = baseAngle + (entity.random.nextDouble() * 2.0 - 1.0) * FALLBACK_SPREAD_RADIANS
        val dir = Vec3(Math.cos(angle), 0.0, Math.sin(angle))
        return BlockPos.containing(entity.position().add(dir.x * FALLBACK_DISTANCE, 0.0, dir.z * FALLBACK_DISTANCE))
    }
}
