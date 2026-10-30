package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * In the Core tasks (`NpcEntity.getCoreTasks()`) rather than an `Activity` of its own: it must keep
 * ticking through every phase with its state intact, the PEEKING window included — a (mutually
 * exclusive) SmartBrainLib `Activity` would `stop()`/`start()` it every time `the cover claim` toggles
 * for a peek, losing `coverTarget`/`phase` right when they need to survive.
 *
 * Full suppression response — not just duck-and-hold: while suppressed, the mob finds a point the
 * threat's last known position can't see, ducks there, then periodically steps back OUT to return
 * fire on its current target before ducking back in, repeating for as long as it stays suppressed.
 *
 * Drives the [NpcActivity.COVER] claim. While it is absent (the PEEKING window),
 * the cover and evade claims are not held, so `GunAttackBehaviour`/`GrenadeThrowBehaviour` take
 * movement, aim and fire back for that window; this behaviour only steps the mob out toward its
 * target for the peek and otherwise keeps out of the way.
 *
 * Digging in: if [CoverSearch.find] finds no real cover, the mob either digs itself a
 * foxhole right where it's standing (if the ground allows — see [CoverMechanics.canDigIn] for the exact gating:
 * badly hurt, a squadmate actually covering it, standable dirt/sand, flat enough ground) or, only
 * when it has no live target at all (genuinely blind, nothing to fight from anywhere), retreats via
 * [CoverMechanics.fallbackAwayFrom] instead — see [tickDiggingIn]/[finishDigging] for the dig itself. An engaged
 * mob (has a target) that can't dig just holds its current spot ([enterHoldingOpen]) rather than
 * fleeing across open ground away from a position it was already fighting from — per user request.
 * A squad that found genuine cover has no need to dig, and digging should never preempt or delay
 * reaching real cover, which is still always tried first.
 *
 * Peeking is a minimal-exposure lean, not a full walk-out (see [CoverPeekSearch]): tries a handful of
 * short steps toward the target (0.5 up to 3 blocks) and takes the first one with a clear line of
 * sight, rather than always closing all the way to the target's own position. This is the standard
 * "smart cover point" idiom from tactical-shooter AI (F.E.A.R.'s cover/lean system is the usual
 * reference: Jeff Orkin, "Three States and a Plan: The AI of F.E.A.R.", GDC 2006) — expose as
 * little of yourself as the terrain actually requires to get a shot, then duck straight back.
 */
class SeekCoverBehaviour : ExtendedBehaviour<NpcEntity>() {

    private enum class Phase {
        MOVING_TO_COVER,
        DIGGING_IN,
        IN_COVER,
        PEEKING,
        RETURNING_TO_COVER,
        DUG_IN_HOLDING,

        // HOLDING_OPEN: engaged (has a target) but no real cover and can't/won't dig further — per
        // user request, hold the current spot and keep firing instead of fleeing across open ground.
        // EXITING_HOLE: dug in, healed back up, trying to actually climb out — see beginExitingHole.
        HOLDING_OPEN,
        EXITING_HOLE,
    }

    // ROOT CAUSE of the whole "digs in / settles into cover, then re-enters fallback retreat"
    // saga: ExtendedBehaviour has an UNDOCUMENTED-to-us default 60-tick runtime cap (runtimeProvider
    // defaults to a constant 60, cooldownProvider to 0 — confirmed via javap on the real jar), which
    // vanilla Behavior.timedOut() enforces completely independently of shouldKeepRunning(). Every
    // start()/stop() pair in the [dig-debug] logs was EXACTLY 61 ticks apart no matter how much
    // suppression time was actually left (proved with the rawRemaining logging added earlier) — none
    // of the suppression-refresh work was ever the actual fix; it was this. noTimeout() sets the
    // runtime cap to Integer.MAX_VALUE so only shouldKeepRunning()/stop() ever end this behaviour.
    init {
        noTimeout()
    }

    private var phase = Phase.MOVING_TO_COVER
    private var coverTarget: BlockPos? = null
    private var pendingCover: BlockPos? = null
    private var pendingFallback: BlockPos? = null
    private var fallbackThreat: Vec3? = null
    private var peekSearch: CoverPeekSearch? = null
    private var phaseUntilTick = 0

    /** Where the peek is going, while [NpcActivity.PEEK] is held. */
    private var peekAt: Vec3? = null

    private fun beginPeek(entity: NpcEntity, point: Vec3) {
        peekAt = point
        entity.claim(NpcActivity.PEEK, this)
    }

    private fun endPeek(entity: NpcEntity) {
        peekAt = null
        entity.release(NpcActivity.PEEK, this)
    }
    private var isFallbackRetreat = false // coverTarget came from fallbackAwayFrom, not CoverSearch.find
    private var blindPeek = false
    private var waitingForPeek = false
    private var nextReplyLogTick = 0
    private val failedCover = HashSet<BlockPos>()

    // How many times this episode has actually dug (capped at CoverMechanics.MAX_DIGS — see that constant and
    // tickDugInHolding's "one extra block, per user request" logic). Replaces a plain boolean: an
    // endless vertical shaft (falling into a fresh hole drops blockPosition() by one, and at that
    // new, deeper spot every canDigIn condition can still hold — same health, same covering ally,
    // flatEnough even MORE easily since the untouched neighbor ground is now higher above it —
    // reported in-game as "уходят в цикл с закапыванием") is exactly what the CoverMechanics.MAX_DIGS cap prevents;
    // a boolean could only ever allow exactly one dig total, which is now one dig short of the
    // explicitly-requested "one extra block if hit again" behaviour.
    private var digsUsed = 0
    private var digTicksRemaining = 0
    private var digPos: BlockPos? = null // block being dug, tracked separately for the progress overlay
    private var healthAtLastDigInCheck = 0f // DUG_IN_HOLDING only — detects a fresh hit for the one-shot extra dig
    private var duggenSideExit = false // EXITING_HOLE only — whether the eye-level escape block has been broken yet

    companion object {
        private const val DWELL_TICKS = 20 // ~1s minimum before the first peek
        private const val DWELL_JITTER = 30 // + up to ~1.5s random, so a squad doesn't peek in lockstep
        private const val PEEK_TICKS = 50 // ~2.5s exposed before ducking back, unless target dies/breaks LOS first
        private const val RECHECK_TICKS = 15 // no target yet — check again soon rather than popping out blind

        // ~3s to try a normal path out of the hole once healed before assuming it's actually stuck
        // and digging an eye-level escape block instead (see beginExitingHole/tickExitingHole).
        private const val EXIT_CHECK_TICKS = 60

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(ModMemories.SUPPRESSING_THREAT.get(), MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = entity.isSuppressed()

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        // Dug in and rested back up past the same threshold that let it dig in the first place —
        // don't stand down immediately: try to actually leave the hole first (see
        // beginExitingHole/tickExitingHole — a CoverMechanics.MAX_DIGS-deep pit isn't always trivially climbable).
        if (phase == Phase.DUG_IN_HOLDING && entity.health >= entity.maxHealth * CoverMechanics.DIG_HEALTH_FRACTION) {
            beginExitingHole(entity)
        }
        if (phase == Phase.EXITING_HOLE) {
            if (hasClimbedOut(entity)) return false // actually out — stop() runs, hands back control normally
            return true
        }
        if (phase == Phase.RETURNING_TO_COVER) return entity.tickCount < phaseUntilTick
        return entity.isSuppressed() || (phase == Phase.PEEKING && entity.tickCount < phaseUntilTick) ||
            (entity.incomingFire.pending(entity.level().gameTime) && knownTarget(entity) == null)
    }

    override fun start(entity: NpcEntity) {
        phase = Phase.MOVING_TO_COVER
        coverTarget = null
        pendingCover = null
        pendingFallback = null
        fallbackThreat = null
        peekSearch = null
        phaseUntilTick = 0
        isFallbackRetreat = false
        blindPeek = false
        waitingForPeek = false
        endPeek(entity)
        failedCover.clear()
        digsUsed = 0
        digTicksRemaining = 0
        digPos = null
        duggenSideExit = false
        entity.release(NpcActivity.DUG_IN, this)
        entity.claim(NpcActivity.COVER, this)
    }

    override fun stop(entity: NpcEntity) {
        endPeek(entity)
        entity.incomingFire.endReply()
        coverTarget = null
        pendingCover = null
        pendingFallback = null
        fallbackThreat = null
        peekSearch = null
        FiringSpots.release(entity.uuid)
        entity.navigation.stop()
        digPos?.let { clearDigProgress(entity) }
        entity.release(NpcActivity.DUG_IN, this)
        entity.release(NpcActivity.COVER, this)
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        // A live grenade outranks cover — GrenadeEvadeBehaviour has the legs until it's clear.
        if (entity.holds(NpcActivity.EVADE)) return
        // DUG_IN_HOLDING/HOLDING_OPEN/EXITING_HOLE deliberately do NOT bail out on threatPos == null
        // the way every other phase does below — none of them are gated by the threat-suppression
        // memory the way MOVING_TO_COVER/IN_COVER/etc. are (see each one's own doc comment).
        when (phase) {
            Phase.DUG_IN_HOLDING -> return tickDugInHolding(entity)
            Phase.HOLDING_OPEN -> return tickHoldingOpen(entity, level)
            Phase.EXITING_HOLE -> return tickExitingHole(entity, level)
            else -> Unit
        }
        val threat = entity.threatPos ?: entity.incomingFire.point(level.gameTime)
        when (phase) {
            Phase.MOVING_TO_COVER -> threat?.let { tickMovingToCover(entity, level, it) }
            Phase.DIGGING_IN -> tickDiggingIn(entity, level)
            Phase.IN_COVER -> tickInCover(entity, level)
            Phase.PEEKING -> tickPeeking(entity)
            Phase.RETURNING_TO_COVER -> tickReturningToCover(entity)
            Phase.DUG_IN_HOLDING, Phase.HOLDING_OPEN, Phase.EXITING_HOLE -> Unit // handled above
        }
    }

    private fun tickMovingToCover(entity: NpcEntity, level: ServerLevel, threat: Vec3) {
        val target = coverTarget
        if (target != null) {
            if (entity.position().closerThan(target.center, 1.5)) {
                // canDigIn checked against the mob's ACTUAL current position, not `target` — arrival
                // only guarantees being within 1.5 blocks of it, and digging itself now happens at
                // the real standing position too (see startDigging's own doc comment) — checking the
                // ground/flatness anywhere else could green-light a dig site different from the one
                // that's actually used.
                // digsUsed < CoverMechanics.MAX_DIGS here too, purely for defense-in-depth: this phase can currently
                // only be reached with digsUsed == 0 (start() resets both together, and nothing else
                // nulls coverTarget mid-episode), but that's an accident of today's control flow, not
                // an explicit guarantee — keep both call sites of canDigIn consistent (PM review finding).
                if (isFallbackRetreat && digsUsed < CoverMechanics.MAX_DIGS && CoverMechanics.canDigIn(entity, level, entity.blockPosition())) {
                    startDigging(entity)
                } else {
                    enterCover(entity, refreshSuppression = true)
                }
            } else if (entity.navigation.isDone && (entity.navigation as? VehicleAwareNavigation)?.canPlan != false) {
                failedCover.add(target)
                coverTarget = null
            }
            return // still travelling this leg either way
        }
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return
        if (fallbackThreat?.distanceToSqr(threat)?.let { it > 64.0 } == true) pendingFallback = null
        if (pendingCover == null && pendingFallback == null) {
            val search = TickBudget.attempt(entity, TickBudget.Work.COVER) { CoverSearch.find(entity, level, threat, failedCover) } ?: return
            pendingCover = search.value
        }
        pendingCover?.let {
            // Waiting for ray admission must not consume one of the four native A* turns.
            // Keep the proposal between queues, then review the live threats and bodies.
            if (!com.sbwnpc.squad.combat.tactics.TacticalBudget.path(entity)) return
            pendingCover = null
            if (!CoverSearch.usable(entity, level, threat, it)) return
            val path = com.sbwnpc.squad.combat.tactics.TacticalPositions.pathToPosition(entity, Vec3.atBottomCenterOf(it))
            if (!com.sbwnpc.squad.combat.tactics.TacticalPositions.reaches(path, Vec3.atBottomCenterOf(it))) {
                failedCover.add(it)
                return
            }
            isFallbackRetreat = false
            coverTarget = it
            FiringSpots.claim(entity.uuid, Vec3.atBottomCenterOf(it))
            entity.navigation.moveTo(path, 1.0)
            CoverMechanics.markCoverChoice(level, it, CoverMechanics.GREEN)
            return
        }
        // Per user request: actively engaged (a live target — near-certainly already holding a
        // GunAttackBehaviour firing position) and no real cover found nearby — don't
        // flee across open ground to a blind fallback point. Dig in right where it's standing if
        // possible, otherwise just hold this spot and keep firing (GunAttackBehaviour already finds
        // the best nearby partial cover to shoot from on its own). Only the "no target at all,
        // genuinely blind" case still falls back to fallbackAwayFrom below.
        if (entity.target != null && (
                !entity.incomingFire.pending(level.gameTime) ||
                    com.sbwnpc.squad.combat.DetectionSightline.canSeeWithin(entity, entity.target!!, NpcEntity.DETECTION_RANGE)
                )
        ) {
            pendingFallback = null
            if (digsUsed < CoverMechanics.MAX_DIGS && CoverMechanics.canDigIn(entity, level, entity.blockPosition())) {
                startDigging(entity)
            } else {
                enterHoldingOpen(entity)
            }
            return
        }
        if (pendingFallback == null) {
            pendingFallback = CoverMechanics.fallbackAwayFrom(entity, threat, failedCover)
            fallbackThreat = threat
        }
        pendingFallback?.let {
            if (!com.sbwnpc.squad.combat.tactics.TacticalBudget.path(entity)) return
            pendingFallback = null
            if (!level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(Vec3.atBottomCenterOf(it)))) {
                failedCover.add(it)
                return
            }
            val path = com.sbwnpc.squad.combat.tactics.TacticalPositions.pathToPosition(entity, Vec3.atBottomCenterOf(it))
            if (!com.sbwnpc.squad.combat.tactics.TacticalPositions.reaches(path, Vec3.atBottomCenterOf(it))) {
                failedCover.add(it)
                return
            }
            isFallbackRetreat = true
            coverTarget = it
            entity.navigation.moveTo(path, 1.0)
            CoverMechanics.markCoverChoice(level, it, CoverMechanics.ORANGE)
            DebugFlags.log(LogGroup.DIG, "{} entered fallback retreat", entity.uuid)
        }
    }

    /** [refreshSuppression] must be true only for a mob's FIRST settle into cover this episode (a
     *  fresh [CoverSearch.find] arrival, or right after [finishDigging]) — see those call sites — never for
     *  the ordinary peek-then-duck-back cycle ([tickReturningToCover]'s call). A PM review caught
     *  that refreshing unconditionally on every re-entry would keep `isSuppressed()` alive
     *  indefinitely for as long as the mob keeps peeking at a live, visible target — quietly
     *  redefining "suppressed" from "recently actually shot at" to "in a prolonged firefight",
     *  which is a much bigger behavioural change than the bug this was meant to fix. Restricting the
     *  refresh to first-settle-only still closes the real gap (suppression lapsing before the mob
     *  ever gets its first dwell/peek check after arriving) without that drift. */
    private fun enterCover(entity: NpcEntity, refreshSuppression: Boolean = false) {
        peekSearch = null
        endPeek(entity)
        blindPeek = false
        waitingForPeek = false
        entity.navigation.stop()
        phase = Phase.IN_COVER
        phaseUntilTick = entity.tickCount + DWELL_TICKS + entity.random.nextInt(DWELL_JITTER)
        if (refreshSuppression) entity.threatPos?.let { entity.suppress(it) }
    }

    private fun startDigging(entity: NpcEntity) {
        entity.navigation.stop()
        phase = Phase.DIGGING_IN
        digTicksRemaining = CoverMechanics.DIG_TICKS
        // Dig the block the mob is ACTUALLY standing on right now, not `target` itself — arrival is
        // only checked within 1.5 blocks of `target` (closerThan in tickMovingToCover), so the mob
        // can legitimately be up to that far from target's exact column when digging starts.
        // Digging target.below() dug a hole next to the mob instead of under its own feet whenever
        // that gap was nonzero — reported in-game as "digs the hole, then just walks off" (it never
        // actually fell in, since the removed block was never the one it was standing on). Also
        // re-anchor coverTarget to this real position so the later peek/duck-back navigation
        // (tickInCover/duckBackToCover/tickReturningToCover) returns to the ACTUAL hole, not the
        // stale original approach point.
        val standingPos = entity.blockPosition()
        digPos = standingPos.below()
        coverTarget = standingPos
        (entity.level() as? ServerLevel)?.let { CoverMechanics.markCoverChoice(it, standingPos, CoverMechanics.BROWN) }
        // Refresh suppression right as digging starts — a secondary safety net (the actual "digs
        // in, then immediately runs off" cause turned out to be ExtendedBehaviour's default 60-tick
        // timeout, see the class doc comment) for the independent, smaller risk that natural
        // suppression genuinely runs out mid-dig if the mob doesn't get hit again for a while.
        entity.threatPos?.let { entity.suppress(it) }
    }

    private fun tickDiggingIn(entity: NpcEntity, level: ServerLevel) {
        val pos = digPos ?: return enterCover(entity) // shouldn't happen, but never get stuck mid-dig
        // Digging takes ~3.5s (CoverMechanics.DIG_TICKS) — long enough that knockback (an explosion, a shove from
        // another mob, getting hit) can push the mob well away from the exact spot it started
        // digging at. Finishing anyway would break a block nobody's standing on/near anymore and
        // then anchor DUG_IN_HOLDING at that same stale spot — reported in-game as the mob just
        // standing exposed in the open with no hole under it after being shoved mid-dig. `coverTarget`
        // was re-anchored to the real standing position in startDigging, so it doubles as the "am I
        // still where I started digging" reference.
        //
        // Exact column (X/Z) equality, not a distance radius (this used to allow up to 1.5 blocks of
        // drift before aborting): falling into the hole only works if the mob is still on the EXACT
        // same 1x1 column when the block breaks — a fractional-block drift well inside any
        // reasonable radius tolerance can still land the mob one column over, next to the hole
        // instead of in it. Reported in-game as sometimes "almost" but not quite falling into a
        // freshly-dug hole on otherwise flat ground, with no other obvious cause. Since this now
        // only trips on an actual column change (not sub-block jitter within the same block), it
        // stays rare — abortDigging's usual full re-evaluation (back to MOVING_TO_COVER) is fine for
        // it.
        //
        // X/Z only, not full BlockPos (PM review finding): a badly-hurt mob taking another hit
        // mid-dig — a likely moment, since digging requires being hurt in the first place — can get
        // a small vertical knockback hop with zero horizontal drift; comparing Y too would abort a
        // perfectly fine dig over a bounce that never actually left the column.
        val anchor = coverTarget
        val currentPos = entity.blockPosition()
        if (anchor != null && (currentPos.x != anchor.x || currentPos.z != anchor.z)) {
            abortDigging(entity)
            return
        }
        digTicksRemaining--
        if (digTicksRemaining <= 0) {
            finishDigging(entity, level)
        } else {
            val stage = (9 - 9 * digTicksRemaining / CoverMechanics.DIG_TICKS).coerceIn(0, 9)
            level.destroyBlockProgress(entity.id, pos, stage)
        }
    }

    /** Bails out of an in-progress dig without finishing it — see tickDiggingIn's displacement check.
     *  Resets to MOVING_TO_COVER with coverTarget cleared so the next tick picks a fresh cover/fallback
     *  spot from wherever the mob actually ended up, rather than trying to resume digging somewhere
     *  it no longer stands. */
    private fun abortDigging(entity: NpcEntity) {
        clearDigProgress(entity)
        digPos = null
        digTicksRemaining = 0
        phase = Phase.MOVING_TO_COVER
        coverTarget = null
    }

    private fun finishDigging(entity: NpcEntity, level: ServerLevel) {
        val pos = digPos ?: return
        clearDigProgress(entity)
        level.destroyBlock(pos, false, entity, 512)
        digPos = null
        digsUsed++
        CoverMechanics.maybeThrowGrenadeOnceDugIn(entity, level)
        enterDugInHolding(entity)
    }

    /** Per user decision: a dug-in mob fights FROM the hole rather than repeating the normal
     *  peek-then-duck-back cycle ([enterCover]/[tickInCover]) — that cycle was built for genuine wall
     *  cover, where popping out to trade shots and ducking back makes sense. Digging only ever
     *  triggers mid-firefight (canDigIn requires an actively-covering ally), so the very next recheck
     *  after finishing would almost always find a live target and immediately walk back out to peek
     *  anyway — reported in-game as "выкопал яму и почти сразу её покинул". Clearing the [NpcActivity.COVER] claim
     *  immediately hands aiming/firing to [GunAttackBehaviour] as normal; [NpcActivity.DUG_IN] is the
     *  new signal that tells it to skip all repositioning (advance/bounding/sidestep) while still
     *  aiming and shooting exactly as it would anywhere else — see that flag's own doc comment. */
    private fun enterDugInHolding(entity: NpcEntity) {
        entity.navigation.stop()
        phase = Phase.DUG_IN_HOLDING
        entity.claim(NpcActivity.DUG_IN, this)
        entity.release(NpcActivity.COVER, this)
        phaseUntilTick = entity.tickCount + RECHECK_TICKS
        healthAtLastDigInCheck = entity.health
    }

    /** Holds the mob in its foxhole for as long as it's both still hurt (below [CoverMechanics.DIG_HEALTH_FRACTION],
     *  the same threshold that let it dig in the first place) and still actively fighting — see
     *  [shouldKeepRunning] for the "healed back up" exit, and below for the "nothing left to fight"
     *  exit. [GunAttackBehaviour] does all the actual aiming/shooting on its own once the [NpcActivity.COVER] claim
     *  is cleared (see [enterDugInHolding]); this just keeps the suppression memory (and therefore
     *  this whole behaviour, and therefore [NpcActivity.DUG_IN]) alive while there's still a live
     *  target to hold position against. Once the target's gone, suppression is deliberately left to
     *  lapse on its own instead of being force-refreshed forever — a mob with nothing shooting back
     *  at it and nothing to shoot at has no reason to stay pinned in a hole. */
    private fun tickDugInHolding(entity: NpcEntity) {
        val target = entity.target
        if (target == null || !target.isAlive) return
        if (entity.tickCount < phaseUntilTick) return
        phaseUntilTick = entity.tickCount + RECHECK_TICKS
        entity.threatPos?.let { entity.suppress(it) }

        // Per user request: one extra block deeper if hit again while already dug in (health
        // dropped since the last check — a fresh hit, not just the same old damage) and the ground
        // still allows it — capped at CoverMechanics.MAX_DIGS total, never unlimited (see digsUsed's own doc
        // comment). Real cover isn't re-checked here: even if some turned up, nothing currently
        // moves a dug-in mob out of its hole to use it, so there'd be no alternative action to
        // take on that information anyway.
        val tookFreshHit = entity.health < healthAtLastDigInCheck
        healthAtLastDigInCheck = entity.health
        if (tookFreshHit && digsUsed < CoverMechanics.MAX_DIGS) {
            val level = entity.level() as? ServerLevel ?: return
            if (CoverMechanics.canDigIn(entity, level, entity.blockPosition())) {
                startDigging(entity)
            }
        }
    }

    /** Per user request: engaged (has a target) with no real cover nearby and unable/unwilling to
     *  dig any further — hold the current spot and keep firing rather than fleeing across open
     *  ground. Clears the [NpcActivity.COVER] claim like [enterDugInHolding] so [GunAttackBehaviour] keeps
     *  full control of aim/fire AND its own partial-cover positioning (`holdFiringPosition`) — unlike
     *  actually digging in, [NpcActivity.DUG_IN] is deliberately NOT set here, since there's no hole
     *  to stay locked into; GunAttackBehaviour is free to reposition within its own small search
     *  radius exactly as it would with no suppression involved at all. */
    private fun enterHoldingOpen(entity: NpcEntity) {
        entity.navigation.stop()
        phase = Phase.HOLDING_OPEN
        entity.release(NpcActivity.COVER, this)
        phaseUntilTick = entity.tickCount + RECHECK_TICKS
    }

    /** Periodically rechecks whether digging has since become viable (still hurt enough, an ally
     *  starts actually covering) — otherwise just keeps suppression alive while there's still a live
     *  target, same idiom as [tickDugInHolding]. No target for a while and this naturally lapses via
     *  [shouldKeepRunning]'s plain `isSuppressed()` fallthrough, same as ordinary IN_COVER/PEEKING. */
    private fun tickHoldingOpen(entity: NpcEntity, level: ServerLevel) {
        val target = entity.target
        if (target == null || !target.isAlive) return
        if (entity.tickCount < phaseUntilTick) return
        phaseUntilTick = entity.tickCount + RECHECK_TICKS
        entity.threatPos?.let { entity.suppress(it) }
        if (digsUsed < CoverMechanics.MAX_DIGS && CoverMechanics.canDigIn(entity, level, entity.blockPosition())) {
            startDigging(entity)
        }
    }

    /** Per user decision ("просто проверять попытку выйти"): healed up and ready to stand down, but
     *  a [CoverMechanics.MAX_DIGS]-deep hole isn't always something a mob can just climb straight out of on its own
     *  (vanilla pathfinding jump-assists one block, not two) — try a normal path out first, and only
     *  if that doesn't actually work within [EXIT_CHECK_TICKS], dig a single escape block at roughly
     *  eye level in [tickExitingHole] instead of leaving it stuck in a hole it can no longer justify
     *  staying in. [shouldKeepRunning] keeps this behaviour alive (returns true) for the whole
     *  attempt via [hasClimbedOut], regardless of suppression state — the mob is done being
     *  suppressed here, it just still needs to physically get out. */
    private fun beginExitingHole(entity: NpcEntity) {
        phase = Phase.EXITING_HOLE
        duggenSideExit = false
        phaseUntilTick = entity.tickCount + EXIT_CHECK_TICKS
        // Must clear BEFORE issuing the exit navigation below — every other system that reads
        // [NpcActivity.DUG_IN] (GunAttackBehaviour first among them) treats it as "hands off this
        // mob's movement" and would otherwise call navigation.stop() on the very same/next tick,
        // canceling the exit attempt before it could ever actually leave.
        entity.release(NpcActivity.DUG_IN, this)
        val level = entity.level() as? ServerLevel
        val hole = coverTarget
        if (level != null && hole != null) {
            val exitPoint = CoverMechanics.holeExit(level, hole)
            if (exitPoint != null) entity.navigation.moveTo(exitPoint.x + 0.5, exitPoint.y.toDouble(), exitPoint.z + 0.5, 1.0)
        }
    }

    /** Risen above the dug bottom's own Y — actually out, regardless of exactly where it ended up. */
    private fun hasClimbedOut(entity: NpcEntity): Boolean {
        val hole = coverTarget ?: return true
        return entity.blockPosition().y > hole.y
    }

    private fun tickExitingHole(entity: NpcEntity, level: ServerLevel) {
        if (entity.tickCount < phaseUntilTick) return
        if (duggenSideExit) return // already tried the escape — nothing more to actively do
        val hole = coverTarget
        // The normal exit path issued in beginExitingHole had its EXIT_CHECK_TICKS chance — if the
        // navigator has already given up (the expected case for a genuinely CoverMechanics.MAX_DIGS-deep pit —
        // vanilla mobs auto-step/jump one block, not two), dig through instead of leaving it stuck.
        if (hole != null && entity.navigation.isDone) {
            CoverMechanics.digSideExit(entity, level, hole)
        }
        duggenSideExit = true
    }

    private fun clearDigProgress(entity: NpcEntity) {
        (entity.level() as? ServerLevel)?.destroyBlockProgress(entity.id, digPos ?: return, -1)
    }

    private fun tickInCover(entity: NpcEntity, level: ServerLevel) {
        if (entity.tickCount < phaseUntilTick) return
        // Re-check dig-in eligibility periodically while sitting at a fallback (not-real-cover)
        // spot — canDigIn used to only ever get checked ONCE, at the exact tick of arrival. A mob
        // that arrived still above the health threshold (or without a covering ally yet) would
        // never dig in later even after taking more fire while just standing there exposed — this
        // closes that gap, at the same cadence as the ordinary recheck/dwell cycle.
        // Checked against the mob's actual current position (it's just standing at coverTarget by
        // now anyway, but see startDigging's doc comment for why this must match exactly).
        // digsUsed < CoverMechanics.MAX_DIGS: without this cap, digging one hole and falling into it
        // (blockPosition() drops by one) re-passes every condition of canDigIn at the new, deeper
        // spot — see digsUsed's own doc comment for the endless-shaft bug this caused.
        if (isFallbackRetreat && digsUsed < CoverMechanics.MAX_DIGS && CoverMechanics.canDigIn(entity, level, entity.blockPosition())) {
            startDigging(entity)
            return
        }
        val known = knownTarget(entity)
        val target = known?.first
        if (target != null && target.isAlive) {
            val peekPoint = coverTarget?.let { peekPoint(entity, level, it, known.second, target, false) }
            if (peekPoint == null) {
                if (peekSearch != null) return // Await admission without restarting the search or dwell.
                phaseUntilTick = entity.tickCount + RECHECK_TICKS
                return
            }
            phase = Phase.PEEKING
            blindPeek = false
            beginPeek(entity, peekPoint.point)
            phaseUntilTick = entity.tickCount + PEEK_TICKS
            // Unlock — GunAttackBehaviour (no longer locked out, see [NpcActivity.COVER]) takes
            // over aiming/approach/fire from here; this is just enough of a nudge to clear
            // whatever's currently blocking sight from the cover point itself.
            entity.release(NpcActivity.COVER, this)
            entity.navigation.moveTo(peekPoint.path, 1.0)
        } else {
            val aim = entity.incomingFire.point(level.gameTime)
            val peek = if (aim != null && entity.incomingFire.pending(level.gameTime) &&
                com.sbwnpc.squad.domain.port.Ports.guns.inHand(entity)?.hasAmmo() == true
            ) {
                coverTarget?.let { blindPeekPoint(entity, level, it, aim) }
            } else {
                null
            }
            if (peek != null) {
                phase = Phase.PEEKING
                blindPeek = true
                waitingForPeek = true
                beginPeek(entity, peek.point)
                phaseUntilTick = entity.tickCount + 100
                entity.navigation.moveTo(peek.path, 1.0)
                DebugFlags.log(LogGroup.ORDER, "[return-fire] {} moving to peek={}", entity.uuid, peek.point)
            } else {
                if (peekSearch != null && aim != null && entity.incomingFire.pending(level.gameTime)) return
                peekSearch = null
                phaseUntilTick = entity.tickCount + RECHECK_TICKS
                if (entity.incomingFire.pending(level.gameTime) && entity.tickCount >= nextReplyLogTick) {
                    nextReplyLogTick = entity.tickCount + 40
                    DebugFlags.log(LogGroup.ORDER, "[return-fire] {} no reachable safe peek, cover={} sector={}", entity.uuid, coverTarget, aim)
                }
            }
        }
    }

    /** Only a real sighting authorizes a precise peek; stored eyes never follow a hidden target. */
    private fun knownTarget(entity: NpcEntity): kotlin.Pair<LivingEntity, Vec3>? {
        val target = entity.target?.takeIf { it.isAlive } ?: return null
        if (com.sbwnpc.squad.combat.DetectionSightline.canSeeWithin(entity, target, NpcEntity.DETECTION_RANGE)) {
            entity.rememberVisible(target)
            return target to target.eyePosition
        }
        return entity.rememberedEye(target)?.let { target to it }
    }

    private fun blindPeekPoint(entity: NpcEntity, level: ServerLevel, cover: BlockPos, aim: Vec3) = peekPoint(entity, level, cover, aim, null, true)

    private fun peekPoint(entity: NpcEntity, level: ServerLevel, cover: BlockPos, aim: Vec3, target: LivingEntity?, blind: Boolean): CoverPeekSearch.Result? {
        val search = peekSearch?.takeIf { it.matches(cover, aim, target, blind) }
            ?: CoverPeekSearch(cover, aim, target?.uuid, blind).also { peekSearch = it }
        val result = search.find(entity, level, aim, target)
        if (result != null || search.exhausted) peekSearch = null
        return result
    }

    private fun tickPeeking(entity: NpcEntity) {
        if (blindPeek && waitingForPeek) {
            if (entity.tickCount >= phaseUntilTick) {
                duckBackToCover(entity)
                return
            }
            val point = peekAt ?: return
            if (entity.position().distanceTo(point) > 1.0) {
                if (entity.navigation.isDone) entity.navigateTo(point, 1.0)
                return
            }
            waitingForPeek = false
            phaseUntilTick = entity.tickCount + 60
            entity.incomingFire.beginReply(entity.level().gameTime)
            entity.release(NpcActivity.COVER, this)
            DebugFlags.log(LogGroup.ORDER, "[return-fire] {} peek ready, rounds={}", entity.uuid, entity.incomingFire.roundsLeft)
        }
        if (blindPeek && !entity.incomingFire.pending(entity.level().gameTime)) {
            duckBackToCover(entity)
            return
        }
        val target = entity.target
        val blindReply = entity.incomingFire.replying(entity.level().gameTime) &&
            (target == null || !com.sbwnpc.squad.combat.DetectionSightline.canSeeWithin(entity, target, NpcEntity.DETECTION_RANGE))
        if (entity.tickCount >= phaseUntilTick || (!blindReply && (target == null || !target.isAlive))) {
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
        entity.navigateTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }

    private fun duckBackToCover(entity: NpcEntity) {
        peekSearch = null
        endPeek(entity)
        waitingForPeek = false
        entity.incomingFire.endReply()
        phase = Phase.RETURNING_TO_COVER
        phaseUntilTick = entity.tickCount + 80
        entity.claim(NpcActivity.COVER, this)
        val target = coverTarget ?: return
        entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
    }
}
