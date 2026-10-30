package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.ActionEvidence
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.tactics.CoverCycle
import com.sbwnpc.squad.ai.tactics.CoverFailure
import com.sbwnpc.squad.ai.tactics.CoverMove
import com.sbwnpc.squad.ai.tactics.DigIn
import com.sbwnpc.squad.ai.tactics.FireSector
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.CoverMechanics
import com.sbwnpc.squad.entity.ai.CoverPeekSearch
import com.sbwnpc.squad.entity.ai.CoverSearch
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The plan's own cover actions (#251). A `FEET` claim they hold does not suspend the man's plan
 * ([NativeTactics.movable]): they are his plan.
 */
internal interface PlanExecutor

/**
 * What a man's cover actions found and did, kept between them by [NativeTactics] and read back as
 * his facts: where his cover is, how many dwell-and-peeks he has done there, whether cover failed,
 * and his foxhole. It owns his [NpcActivity.DUG_IN] claim from the dig until he climbs out, as part
 * of his plan.
 */
internal class CoverState : PlanExecutor {
    var cover: BlockPos? = null
    var coverSector: FireSector? = null
    var cycles = 0
    var failure: CoverFailure? = null
    var hole: BlockPos? = null
    var digs = 0
    var healthAtDig = 0f
    private var fireFrom: Vec3? = null
    private var fire: FireSector? = null

    /**
     * The sector of fire from [threat], kept while the fire stays within [FireSector.GRID] of where
     * the sector was taken: the bearing of each hit is rounded, and a point jittering across a cell
     * boundary would otherwise replan him in the middle of his move to cover.
     */
    fun fireSector(threat: Vec3): FireSector {
        val kept = fire
        val from = fireFrom
        if (kept != null && from != null && Vec3(threat.x - from.x, 0.0, threat.z - from.z).length() <= FireSector.GRID) return kept
        fireFrom = threat
        return FireSector.of(threat.x, threat.z).also { fire = it }
    }

    /** He stands at the cover his last move found, from the fire it was found for. */
    fun sheltered(npc: NpcEntity, sector: FireSector?) = cover?.let { on(npc, it) } == true && sector != null && sector == coverSector

    fun leftHole() {
        hole = null
        digs = 0
    }

    companion object {
        const val ARRIVAL = 1.5

        /**
         * Whether he stands in [cell] itself. Cover is checked for that cell only: a step beside it,
         * where the path to it ends (#251's take-cover scene stopped a block short at a wall's end),
         * can still be in the line of fire.
         */
        fun on(npc: NpcEntity, cell: BlockPos): Boolean {
            val at = npc.position()
            val center = Vec3.atBottomCenterOf(cell)
            return abs(at.x - center.x) <= IN_CELL && abs(at.z - center.z) <= IN_CELL && abs(at.y - center.y) < 1.0
        }

        /** Half a block: he is within the cell's own column. */
        private const val IN_CELL = 0.5
    }
}

/** Claims [kind] for its action while it runs and lets it go however the action ends. */
internal class Claim(private val npc: NpcEntity, private val owner: Any) {
    private val held = HashSet<NpcActivity>()

    fun take(kind: NpcActivity) {
        if (held.add(kind)) npc.claim(kind, owner)
    }

    fun drop(kind: NpcActivity) {
        if (held.remove(kind)) npc.release(kind, owner)
    }

    fun dropAll() {
        held.toList().forEach(::drop)
    }
}

/**
 * Reports an action complete one tick after it finished. The runtime checks the completion against
 * the facts observed before the action ticked, so what the action has just recorded in [CoverState]
 * shows only on the next tick; reported at once, the completion is unconfirmed, and the runtime
 * suspends the action (its claims released) and ticks it again, a dig digging a second block.
 */
internal class Finish {
    private var evidence: ActionEvidence? = null

    /** Finished now: [ready] completes on the next tick. */
    fun now(kind: String, now: Long): ActionUpdate {
        evidence = ActionEvidence(kind, now)
        return ActionUpdate.Running
    }

    fun ready(): ActionUpdate? = evidence?.let { ActionUpdate.Completed(listOf(it)) }
}

/**
 * A cover action's claims and its completion. However the runtime pauses or ends it, its claims go,
 * and it takes [NpcActivity.COVER] back on resuming.
 */
internal abstract class CoverAction(protected val npc: NpcEntity) :
    ActionExecution,
    PlanExecutor {
    protected val claim = Claim(npc, this)
    protected val finish = Finish()

    /**
     * The completion [finish] holds, with the claims let go: the runtime disposes of a completed
     * action only when the plan is replaced, and a claim left would keep the gun behaviour off him
     * through every peek of the next cycle.
     */
    protected fun finished(): ActionUpdate? = finish.ready()?.also { claim.dropAll() }

    /** Undoes what else the action started: on a pause or cancel when [paused], on disposal otherwise. */
    protected open fun release(paused: Boolean) = Unit

    override fun suspend(now: Long, reason: String) {
        claim.dropAll()
        release(paused = true)
    }

    override fun resume(now: Long) = claim.take(NpcActivity.COVER)

    override fun cancel(now: Long, reason: String) = suspend(now, reason)

    override fun dispose(now: Long, reason: String) {
        claim.dropAll()
        release(paused = false)
    }
}

/** A path to the middle of [cell]. */
private fun NpcEntity.walkTo(cell: BlockPos) {
    val center = Vec3.atBottomCenterOf(cell)
    navigation.moveTo(center.x, center.y, center.z, 1.0)
}

/** The last step onto [cell], walked directly: a path that close ends a block short of it. */
private fun NpcEntity.stepOnto(cell: BlockPos) {
    val center = Vec3.atBottomCenterOf(cell)
    moveControl.setWantedPosition(center.x, center.y, center.z, 1.0)
}

/**
 * Walks to a point hidden from the fire, found by [CoverSearch], or with [CoverMove.fallBack] to a
 * point away from it ([CoverMechanics.fallbackAwayFrom]). It decides nothing: when no cover is found
 * or none can be reached it records the failure and fails, and the plan chooses what next.
 */
internal class NativeCoverMove(private val move: CoverMove, npc: NpcEntity, private val state: CoverState, private val threat: () -> Vec3?) : CoverAction(npc) {
    private val failed = HashSet<BlockPos>()
    private var target: BlockPos? = null
    private var stepping = 0

    override fun start(now: Long): ActionUpdate {
        claim.take(NpcActivity.COVER)
        state.cover = null
        return tick(now)
    }

    override fun tick(now: Long): ActionUpdate {
        finished()?.let { return it }
        val level = npc.level() as? ServerLevel ?: return ActionUpdate.Failed("no_level")
        val from = threat() ?: return ActionUpdate.Failed("no_threat")
        target?.let { return walk(it, now) }
        if ((npc.navigation as? VehicleAwareNavigation)?.canPlan == false) return ActionUpdate.Running
        // Already hidden where he stands: no walk, and the dwell starts here.
        if (!move.fallBack && CoverSearch.usable(npc, level, from, npc.blockPosition())) return arrived(npc.blockPosition(), now)
        return search(level, from, now)
    }

    /** Finds the next point and sets off for it, or records the failure once there is none. */
    private fun search(level: ServerLevel, from: Vec3, now: Long): ActionUpdate {
        val found = if (move.fallBack) {
            CoverMechanics.fallbackAwayFrom(npc, from, failed)
        } else {
            (TickBudget.attempt(npc, TickBudget.Work.COVER) { CoverSearch.find(npc, level, from, failed) } ?: return ActionUpdate.Running).value
        }
        if (found == null || failed.size >= MAX_UNREACHABLE) return fail(now, if (found == null) "no_cover" else "cover_unreachable")
        if (!TacticalBudget.path(npc)) return ActionUpdate.Running
        val point = Vec3.atBottomCenterOf(found)
        val path = TacticalPositions.pathToPosition(npc, point)
        if ((!move.fallBack && !CoverSearch.usable(npc, level, from, found)) || !TacticalPositions.reaches(path, point)) {
            DebugFlags.log(LogGroup.DIG, "{} cover point {} rejected: path end {} canReach={}", npc.uuid, found, path?.endNode, path?.canReach())
            failed += found
            return ActionUpdate.Running
        }
        target = found
        if (!move.fallBack) FiringSpots.claim(npc.uuid, point)
        npc.navigation.moveTo(path, 1.0)
        CoverMechanics.markCoverChoice(level, found, if (move.fallBack) CoverMechanics.ORANGE else CoverMechanics.GREEN)
        return ActionUpdate.Running
    }

    private fun walk(to: BlockPos, now: Long): ActionUpdate {
        if (CoverState.on(npc, to)) return arrived(to, now)
        if (!npc.navigation.isDone || (npc.navigation as? VehicleAwareNavigation)?.canPlan == false) return ActionUpdate.Running
        // The path ends a block short of a target that close; the last step is walked directly.
        if (npc.position().closerThan(to.center, CoverState.ARRIVAL + 1.0) && stepping++ < STEP_TICKS) {
            npc.stepOnto(to)
            return ActionUpdate.Running
        }
        DebugFlags.log(LogGroup.DIG, "{} cover point {} unreached from {} after {} step ticks", npc.uuid, to, npc.position(), stepping)
        failed += to
        target = null
        stepping = 0
        return ActionUpdate.Running
    }

    private fun arrived(at: BlockPos, now: Long): ActionUpdate {
        npc.navigation.stop()
        state.cover = at
        state.coverSector = move.sector
        state.failure = null
        return finish.now(if (move.fallBack) "fell_back" else "in_cover", now)
    }

    private fun fail(now: Long, reason: String): ActionUpdate {
        state.failure = CoverFailure(move.sector, now)
        DebugFlags.log(LogGroup.DIG, "{} cover move failed: {} tried={}", npc.uuid, reason, failed.size)
        return ActionUpdate.Failed(reason)
    }

    override fun release(paused: Boolean) {
        if (paused) npc.navigation.stop()
    }

    private companion object {
        /** Cover points found but not reached before the move counts as failed. */
        const val MAX_UNREACHABLE = 4

        /** Ticks of a direct last step onto the cover cell before it counts as unreached. */
        const val STEP_TICKS = 20
    }
}

/**
 * One dwell in cover, then a peek and fire from a point beside it ([CoverPeekSearch]), or a blind
 * reply at unseen fire, then back. The timings are the cover behaviour's; when the cycle is over it
 * counts it, and the plan looks again.
 */
internal class NativeCoverCycle(private val cycle: CoverCycle, npc: NpcEntity, private val state: CoverState) : CoverAction(npc) {
    private enum class Phase { DWELL, PEEK_OUT, PEEKING, BACK }

    private var phase = Phase.DWELL
    private var until = 0
    private var peekAt: Vec3? = null
    private var blind = false
    private val points = PeekPoints(npc)

    override fun start(now: Long): ActionUpdate {
        claim.take(NpcActivity.COVER)
        phase = Phase.DWELL
        until = npc.tickCount + DWELL_TICKS + npc.random.nextInt(DWELL_JITTER)
        return ActionUpdate.Running
    }

    override fun tick(now: Long): ActionUpdate {
        finished()?.let { return it }
        val level = npc.level() as? ServerLevel ?: return ActionUpdate.Failed("no_level")
        val cover = state.cover ?: return done(now)
        return when (phase) {
            Phase.DWELL -> dwell(level, cover, now)
            Phase.PEEK_OUT -> peekOut(now)
            Phase.PEEKING -> peeking(now)
            Phase.BACK -> back(cover, now)
        }
    }

    private fun dwell(level: ServerLevel, cover: BlockPos, now: Long): ActionUpdate {
        if (npc.tickCount < until) return ActionUpdate.Running
        val known = points.knownTarget()
        if (known != null) {
            val point = points.find(level, cover, known.second, known.first, blind = false) ?: return if (points.searching) ActionUpdate.Running else done(now)
            blind = false
            return beginPeek(point, PEEK_TICKS)
        }
        val aim = npc.incomingFire.point(level.gameTime)
        if (aim != null && npc.incomingFire.pending(level.gameTime) && Ports.guns.inHand(npc)?.hasAmmo() == true) {
            val point = points.find(level, cover, aim, null, blind = true) ?: return if (points.searching) ActionUpdate.Running else done(now)
            blind = true
            phase = Phase.PEEK_OUT
            peekAt = point.point
            claim.take(NpcActivity.PEEK)
            until = npc.tickCount + BLIND_PEEK_TICKS
            npc.navigation.moveTo(point.path, 1.0)
            return ActionUpdate.Running
        }
        return done(now)
    }

    private fun beginPeek(point: CoverPeekSearch.Result, ticks: Int): ActionUpdate {
        phase = Phase.PEEKING
        peekAt = point.point
        claim.take(NpcActivity.PEEK)
        // The gun behaviour takes the aim and the shot once cover lets go of it.
        claim.drop(NpcActivity.COVER)
        until = npc.tickCount + ticks
        npc.navigation.moveTo(point.path, 1.0)
        return ActionUpdate.Running
    }

    /** A blind peek walks out first, then replies to the fire's last sector. */
    private fun peekOut(now: Long): ActionUpdate {
        if (npc.tickCount >= until) return duck(now)
        val point = peekAt ?: return duck(now)
        if (npc.position().distanceTo(point) > 1.0) {
            if (npc.navigation.isDone) npc.navigateTo(point, 1.0)
            return ActionUpdate.Running
        }
        phase = Phase.PEEKING
        until = npc.tickCount + BLIND_REPLY_TICKS
        npc.incomingFire.beginReply(npc.level().gameTime)
        claim.drop(NpcActivity.COVER)
        return ActionUpdate.Running
    }

    private fun peeking(now: Long): ActionUpdate {
        val gameTime = npc.level().gameTime
        if (blind && !npc.incomingFire.pending(gameTime)) return duck(now)
        val target = npc.target?.takeIf { it.isAlive }
        val blindReply = npc.incomingFire.replying(gameTime) && (target == null || !DetectionSightline.canSeeWithin(npc, target, NpcEntity.DETECTION_RANGE))
        if (npc.tickCount >= until || (!blindReply && target == null)) return duck(now)
        return ActionUpdate.Running
    }

    private fun duck(now: Long): ActionUpdate {
        points.reset()
        peekAt = null
        claim.drop(NpcActivity.PEEK)
        npc.incomingFire.endReply()
        claim.take(NpcActivity.COVER)
        phase = Phase.BACK
        until = npc.tickCount + BACK_TICKS
        val cover = state.cover ?: return done(now)
        npc.walkTo(cover)
        return ActionUpdate.Running
    }

    private fun back(cover: BlockPos, now: Long): ActionUpdate {
        if (CoverState.on(npc, cover) || npc.tickCount >= until) return done(now)
        if (npc.navigation.isDone && npc.position().closerThan(cover.center, CoverState.ARRIVAL + 1.0)) {
            npc.stepOnto(cover)
        } else {
            npc.navigateTo(Vec3.atBottomCenterOf(cover), 1.0)
        }
        return ActionUpdate.Running
    }

    private fun done(now: Long): ActionUpdate {
        npc.navigation.stop()
        state.cycles = cycle.number + 1
        return finish.now("cover_cycle", now)
    }

    override fun release(paused: Boolean) {
        npc.incomingFire.endReply()
        if (paused) npc.navigation.stop()
    }

    private companion object {
        const val DWELL_TICKS = 20 // ~1s minimum before the first peek
        const val DWELL_JITTER = 30 // + up to ~1.5s random, so a squad doesn't peek in lockstep
        const val PEEK_TICKS = 50 // ~2.5s exposed before ducking back, unless target dies/breaks LOS first
        const val BLIND_PEEK_TICKS = 100
        const val BLIND_REPLY_TICKS = 60
        const val BACK_TICKS = 80
    }
}

/** Where a peek from cover goes: a search spread over ticks ([CoverPeekSearch]) and what it aims at. */
private class PeekPoints(private val npc: NpcEntity) {
    private var search: CoverPeekSearch? = null

    /** A search is under way and has more points to try. */
    val searching get() = search != null

    /** Only a real sighting authorizes a precise peek; stored eyes never follow a hidden target. */
    fun knownTarget(): Pair<LivingEntity, Vec3>? {
        val target = npc.target?.takeIf { it.isAlive } ?: return null
        if (DetectionSightline.canSeeWithin(npc, target, NpcEntity.DETECTION_RANGE)) {
            npc.rememberVisible(target)
            return target to target.eyePosition
        }
        return npc.rememberedEye(target)?.let { target to it }
    }

    fun find(level: ServerLevel, cover: BlockPos, aim: Vec3, target: LivingEntity?, blind: Boolean): CoverPeekSearch.Result? {
        val current = search?.takeIf { it.matches(cover, aim, target, blind) } ?: CoverPeekSearch(cover, aim, target?.uuid, blind).also { search = it }
        val result = current.find(npc, level, aim, target)
        if (result != null || current.exhausted) search = null
        return result
    }

    fun reset() {
        search = null
    }
}

/**
 * Digs one block of a foxhole under him, as the cover behaviour does, and fails if he is pushed off
 * the column while digging. The plan chose to dig; the hole and its depth are recorded in [state].
 */
internal class NativeDig(private val dig: DigIn, npc: NpcEntity, private val state: CoverState) : CoverAction(npc) {
    private var column: BlockPos? = null
    private var block: BlockPos? = null
    private var left = 0

    override fun start(now: Long): ActionUpdate {
        npc.navigation.stop()
        claim.take(NpcActivity.COVER)
        val standing = npc.blockPosition()
        column = standing
        block = standing.below()
        left = CoverMechanics.DIG_TICKS
        (npc.level() as? ServerLevel)?.let { CoverMechanics.markCoverChoice(it, standing, CoverMechanics.BROWN) }
        return ActionUpdate.Running
    }

    override fun tick(now: Long): ActionUpdate {
        finished()?.let { return it }
        val level = npc.level() as? ServerLevel ?: return ActionUpdate.Failed("no_level")
        val at = column ?: return ActionUpdate.Failed("no_dig_site")
        val pos = block ?: return ActionUpdate.Failed("no_dig_site")
        val current = npc.blockPosition()
        // Pushed off the column, the hole would open beside him: see SeekCoverBehaviour's dig.
        if (current.x != at.x || current.z != at.z) {
            level.destroyBlockProgress(npc.id, pos, -1)
            return ActionUpdate.Failed("displaced")
        }
        if (--left > 0) {
            level.destroyBlockProgress(npc.id, pos, (CRACK_STAGES - CRACK_STAGES * left / CoverMechanics.DIG_TICKS).coerceIn(0, CRACK_STAGES))
            return ActionUpdate.Running
        }
        level.destroyBlockProgress(npc.id, pos, -1)
        level.destroyBlock(pos, false, npc, DESTROY_UPDATE_LIMIT)
        state.hole = at
        state.digs = dig.number + 1
        state.healthAtDig = npc.health
        CoverMechanics.maybeThrowGrenadeOnceDugIn(npc, level)
        claim.drop(NpcActivity.COVER)
        npc.claim(NpcActivity.DUG_IN, state)
        return finish.now("dug", now)
    }

    override fun release(paused: Boolean) {
        block?.let { (npc.level() as? ServerLevel)?.destroyBlockProgress(npc.id, it, -1) }
    }

    private companion object {
        /** The last crack stage the client draws on a block being broken. */
        const val CRACK_STAGES = 9

        /** `Level.destroyBlock`'s neighbour-update budget, as the cover behaviour's dig passes it. */
        const val DESTROY_UPDATE_LIMIT = 512
    }
}

/**
 * Stays in the hole: [NpcActivity.DUG_IN], held by the hole itself ([CoverState]) from the dig until
 * he climbs out, keeps the gun behaviour from moving him while it aims and fires from there.
 */
internal class NativeHoldHole(private val npc: NpcEntity, private val state: CoverState) :
    ActionExecution,
    PlanExecutor {
    override fun start(now: Long): ActionUpdate {
        npc.navigation.stop()
        // A hit the plan has answered (no deeper dig was possible) is not news to the next hold.
        state.healthAtDig = npc.health
        return ActionUpdate.Running
    }

    override fun tick(now: Long): ActionUpdate = ActionUpdate.Running

    override fun suspend(now: Long, reason: String) = Unit

    override fun resume(now: Long) = Unit

    override fun cancel(now: Long, reason: String) = Unit
}

/**
 * Climbs out of the hole: a path to the ground beside it first, and if the navigator gives up in a
 * pit two deep, the escape the cover behaviour digs ([CoverMechanics.digSideExit]).
 */
internal class NativeLeaveHole(private val npc: NpcEntity, private val state: CoverState) :
    ActionExecution,
    PlanExecutor {
    private var until = 0
    private var dugOut = false
    private val finish = Finish()

    override fun start(now: Long): ActionUpdate {
        // Out of the hole's claim first: the gun behaviour stops a dug-in man's navigation.
        npc.release(NpcActivity.DUG_IN, state)
        until = npc.tickCount + EXIT_CHECK_TICKS
        val level = npc.level() as? ServerLevel
        val hole = state.hole
        if (level != null && hole != null) CoverMechanics.holeExit(level, hole)?.let(npc::walkTo)
        return tick(now)
    }

    override fun tick(now: Long): ActionUpdate {
        finish.ready()?.let { return it }
        val hole = state.hole
        if (hole == null || npc.blockPosition().y > hole.y) {
            state.leftHole()
            return finish.now("out_of_hole", now)
        }
        if (npc.tickCount >= until && !dugOut && npc.navigation.isDone) {
            (npc.level() as? ServerLevel)?.let { CoverMechanics.digSideExit(npc, it, hole) }
            dugOut = true
        }
        return ActionUpdate.Running
    }

    override fun suspend(now: Long, reason: String) = Unit

    override fun resume(now: Long) = Unit

    override fun cancel(now: Long, reason: String) = Unit

    private companion object {
        /** ~3 s to walk out normally before digging an escape. */
        const val EXIT_CHECK_TICKS = 60
    }
}
