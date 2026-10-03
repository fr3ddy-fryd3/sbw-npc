package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
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
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * In the Core tasks (`NpcEntity.getCoreTasks()`) rather than an `Activity` of its own: it must keep
 * ticking through every phase with its state intact, the PEEKING window included — a (mutually
 * exclusive) SmartBrainLib `Activity` would `stop()`/`start()` it every time `COVER_HOLD` toggles
 * for a peek, losing `coverTarget`/`phase` right when they need to survive.
 *
 * Full suppression response — not just duck-and-hold: while suppressed, the mob finds a point the
 * threat's last known position can't see, ducks there, then periodically steps back OUT to return
 * fire on its current target before ducking back in, repeating for as long as it stays suppressed.
 *
 * Drives [ModMemories.COVER_HOLD]. While it is absent (the PEEKING window),
 * `NpcEntity.combatLockedByCover()` is false, so `GunAttackBehaviour`/`GrenadeThrowBehaviour` take
 * movement, aim and fire back for that window; this behaviour only steps the mob out toward its
 * target for the peek and otherwise keeps out of the way.
 *
 * Digging in: if [CoverSearch.find] finds no real cover, the mob either digs itself a
 * foxhole right where it's standing (if the ground allows — see [canDigIn] for the exact gating:
 * badly hurt, a squadmate actually covering it, standable dirt/sand, flat enough ground) or, only
 * when it has no live target at all (genuinely blind, nothing to fight from anywhere), retreats via
 * [fallbackAwayFrom] instead — see [tickDiggingIn]/[finishDigging] for the dig itself. An engaged
 * mob (has a target) that can't dig just holds its current spot ([enterHoldingOpen]) rather than
 * fleeing across open ground away from a position it was already fighting from — per user request.
 * A squad that found genuine cover has no need to dig, and digging should never preempt or delay
 * reaching real cover, which is still always tried first.
 *
 * Peeking is a minimal-exposure lean, not a full walk-out (see [findPeekPoint]): tries a handful of
 * short steps toward the target (0.5 up to 3 blocks) and takes the first one with a clear line of
 * sight, rather than always closing all the way to the target's own position. This is the standard
 * "smart cover point" idiom from tactical-shooter AI (F.E.A.R.'s cover/lean system is the usual
 * reference: Jeff Orkin, "Three States and a Plan: The AI of F.E.A.R.", GDC 2006) — expose as
 * little of yourself as the terrain actually requires to get a shot, then duck straight back.
 */
class SeekCoverBehaviour : ExtendedBehaviour<NpcEntity>() {

    private enum class Phase {
        MOVING_TO_COVER, DIGGING_IN, IN_COVER, PEEKING, RETURNING_TO_COVER, DUG_IN_HOLDING,
        // HOLDING_OPEN: engaged (has a target) but no real cover and can't/won't dig further — per
        // user request, hold the current spot and keep firing instead of fleeing across open ground.
        // EXITING_HOLE: dug in, healed back up, trying to actually climb out — see beginExitingHole.
        HOLDING_OPEN, EXITING_HOLE
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
    private var phaseUntilTick = 0
    private var isFallbackRetreat = false // coverTarget came from fallbackAwayFrom, not CoverSearch.find
    // How many times this episode has actually dug (capped at MAX_DIGS — see that constant and
    // tickDugInHolding's "one extra block, per user request" logic). Replaces a plain boolean: an
    // endless vertical shaft (falling into a fresh hole drops blockPosition() by one, and at that
    // new, deeper spot every canDigIn condition can still hold — same health, same covering ally,
    // flatEnough even MORE easily since the untouched neighbor ground is now higher above it —
    // reported in-game as "уходят в цикл с закапыванием") is exactly what the MAX_DIGS cap prevents;
    // a boolean could only ever allow exactly one dig total, which is now one dig short of the
    // explicitly-requested "one extra block if hit again" behaviour.
    private var digsUsed = 0
    private var digTicksRemaining = 0
    private var digPos: BlockPos? = null // block being dug, tracked separately for the progress overlay
    private var healthAtLastDigInCheck = 0f // DUG_IN_HOLDING only — detects a fresh hit for the one-shot extra dig
    private var duggenSideExit = false // EXITING_HOLE only — whether the eye-level escape block has been broken yet

    companion object {
        private const val FALLBACK_DISTANCE = 6.0
        private const val FALLBACK_SPREAD_RADIANS = Math.PI / 3.0 // +/- 60 deg off dead-away-from-threat
        private const val DWELL_TICKS = 20        // ~1s minimum before the first peek
        private const val DWELL_JITTER = 30        // + up to ~1.5s random, so a squad doesn't peek in lockstep
        private const val PEEK_TICKS = 50          // ~2.5s exposed before ducking back, unless target dies/breaks LOS first
        private const val RECHECK_TICKS = 15       // no target yet — check again soon rather than popping out blind

        // Minimal-exposure peek distances (blocks), tried in order — first one with a clear
        // raycast to the target wins. Lean-and-peek rather than a full walk-out to the target, per
        // user request (and how tactical-shooter AI cover systems like F.E.A.R.'s generally do it —
        // see this class's own doc comment for the research pointer): expose as little as the
        // terrain requires, not "walk all the way out into the open".
        private val PEEK_STEP_DISTANCES = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0)

        private const val DIG_HEALTH_FRACTION = 0.25f // only badly hurt NPCs bother digging in (was 0.5, per user request)
        private const val DIG_TICKS = 70              // ~3.5s of "digging" before the hole is done
        // One initial dig, plus at most one extra block deeper if hit again while dug in and still
        // no real cover — per user request. NOT unlimited (see digsUsed's own doc comment for why).
        private const val MAX_DIGS = 2
        // ~3s to try a normal path out of the hole once healed before assuming it's actually stuck
        // and digging an eye-level escape block instead (see beginExitingHole/tickExitingHole).
        private const val EXIT_CHECK_TICKS = 60
        private const val COVERING_ALLY_RADIUS = 16.0

        // Debug-visual colors (see markCoverChoice) — GREEN for a real found-cover spot, ORANGE for
        // a blind fallback retreat, BROWN for an actually-started dig. Gated on DebugFlags.MARKERS_ENABLED.
        private val GREEN = org.joml.Vector3f(0.2f, 1.0f, 0.2f)
        private val ORANGE = org.joml.Vector3f(1.0f, 0.6f, 0.0f)
        private val BROWN = org.joml.Vector3f(0.55f, 0.35f, 0.1f)

        private const val COVERING_FIRE_WINDOW_TICKS = 40 // ~2s — covers gaps between shots, not just a single tick

        // See maybeThrowGrenadeOnceDugIn().
        private const val GRENADE_THROW_CHANCE = 0.2

        private val NEIGHBOR_OFFSETS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        // 8-directional (incl. diagonals) — used to confirm the ground is actually flat around the
        // dig site, not just "hidden", see isFlatEnoughToDig().
        private val DIG_NEIGHBOR_OFFSETS = listOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1
        )

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(ModMemories.SUPPRESSING_THREAT.get(), MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = entity.isSuppressed()

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        // Dug in and rested back up past the same threshold that let it dig in the first place —
        // don't stand down immediately: try to actually leave the hole first (see
        // beginExitingHole/tickExitingHole — a MAX_DIGS-deep pit isn't always trivially climbable).
        if (phase == Phase.DUG_IN_HOLDING && entity.health >= entity.maxHealth * DIG_HEALTH_FRACTION) {
            beginExitingHole(entity)
        }
        if (phase == Phase.EXITING_HOLE) {
            if (hasClimbedOut(entity)) return false // actually out — stop() runs, hands back control normally
            return true
        }
        return entity.isSuppressed()
    }

    override fun start(entity: NpcEntity) {
        phase = Phase.MOVING_TO_COVER
        coverTarget = null
        phaseUntilTick = 0
        isFallbackRetreat = false
        digsUsed = 0
        digTicksRemaining = 0
        digPos = null
        duggenSideExit = false
        entity.diggedIn = false
        BrainUtils.setMemory(entity, ModMemories.COVER_HOLD.get(), true)
    }

    override fun stop(entity: NpcEntity) {
        coverTarget = null
        FiringSpots.release(entity.uuid)
        entity.navigation.stop()
        digPos?.let { clearDigProgress(entity) }
        entity.diggedIn = false
        BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        // A live grenade outranks cover — GrenadeEvadeBehaviour has the legs until it's clear.
        if (entity.evadingGrenade()) return
        // DUG_IN_HOLDING/HOLDING_OPEN/EXITING_HOLE deliberately do NOT bail out on threatPos == null
        // the way every other phase does below — none of them are gated by the threat-suppression
        // memory the way MOVING_TO_COVER/IN_COVER/etc. are (see each one's own doc comment).
        when (phase) {
            Phase.DUG_IN_HOLDING -> return tickDugInHolding(entity)
            Phase.HOLDING_OPEN -> return tickHoldingOpen(entity, level)
            Phase.EXITING_HOLE -> return tickExitingHole(entity, level)
            else -> Unit
        }
        val threat = entity.threatPos ?: return
        when (phase) {
            Phase.MOVING_TO_COVER -> tickMovingToCover(entity, level, threat)
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
                // digsUsed < MAX_DIGS here too, purely for defense-in-depth: this phase can currently
                // only be reached with digsUsed == 0 (start() resets both together, and nothing else
                // nulls coverTarget mid-episode), but that's an accident of today's control flow, not
                // an explicit guarantee — keep both call sites of canDigIn consistent (PM review finding).
                if (isFallbackRetreat && digsUsed < MAX_DIGS && canDigIn(entity, level, entity.blockPosition())) {
                    startDigging(entity)
                } else {
                    enterCover(entity, refreshSuppression = true)
                }
            }
            return // still travelling this leg either way
        }
        // Global raycast budget (TickBudget): one mortar shell suppresses a whole squad on the
        // same tick, and each cover search is a grid of candidates each raycast against every
        // nearby threat. If this tick is spent, just try again next tick — the mob is suppressed
        // and standing still either way, one tick of delay is invisible.
        if (!TickBudget.hasRaycasts(level)) return
        CoverSearch.find(entity, level, threat)?.let {
            isFallbackRetreat = false
            coverTarget = it
            FiringSpots.claim(entity.uuid, it.bottomCenter)
            entity.navigation.moveTo(it.x + 0.5, it.y.toDouble(), it.z + 0.5, 1.0)
            markCoverChoice(level, it, GREEN)
            return
        }
        // Per user request: actively engaged (a live target — near-certainly already holding a
        // GunAttackBehaviour firing position) and no real cover found nearby — don't
        // flee across open ground to a blind fallback point. Dig in right where it's standing if
        // possible, otherwise just hold this spot and keep firing (GunAttackBehaviour already finds
        // the best nearby partial cover to shoot from on its own). Only the "no target at all,
        // genuinely blind" case still falls back to fallbackAwayFrom below.
        if (entity.target != null) {
            if (digsUsed < MAX_DIGS && canDigIn(entity, level, entity.blockPosition())) {
                startDigging(entity)
            } else {
                enterHoldingOpen(entity)
            }
            return
        }
        fallbackAwayFrom(entity, threat)?.let {
            isFallbackRetreat = true
            coverTarget = it
            entity.navigation.moveTo(it.x + 0.5, it.y.toDouble(), it.z + 0.5, 1.0)
            markCoverChoice(level, it, ORANGE)
            DebugFlags.log(LogGroup.DIG, "{} entered fallback retreat", entity.uuid)
        }
    }

    /** Player-facing visual, per user request — a short particle burst at the exact block chosen as
     *  cover, GREEN for a real [CoverSearch.find] hit (a wall the raycast actually verified blocks the
     *  threat), ORANGE for a [fallbackAwayFrom] retreat (no real cover found nearby at all), BROWN
     *  for an actually-started dig. Gated on [DebugFlags.MARKERS_ENABLED] — one place to toggle these
     *  off, since the user expects to do that fairly often (e.g. playing with a friend). */
    private fun markCoverChoice(level: ServerLevel, pos: BlockPos, color: org.joml.Vector3f) {
        if (!DebugFlags.MARKERS_ENABLED) return
        level.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(color, 1.5f),
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 12, 0.3, 0.3, 0.3, 0.0
        )
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
        entity.navigation.stop()
        phase = Phase.IN_COVER
        phaseUntilTick = entity.tickCount + DWELL_TICKS + entity.random.nextInt(DWELL_JITTER)
        if (refreshSuppression) entity.threatPos?.let { entity.suppress(it) }
    }

    private fun startDigging(entity: NpcEntity) {
        entity.navigation.stop()
        phase = Phase.DIGGING_IN
        digTicksRemaining = DIG_TICKS
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
        (entity.level() as? ServerLevel)?.let { markCoverChoice(it, standingPos, BROWN) }
        // Refresh suppression right as digging starts — a secondary safety net (the actual "digs
        // in, then immediately runs off" cause turned out to be ExtendedBehaviour's default 60-tick
        // timeout, see the class doc comment) for the independent, smaller risk that natural
        // suppression genuinely runs out mid-dig if the mob doesn't get hit again for a while.
        entity.threatPos?.let { entity.suppress(it) }
    }

    private fun tickDiggingIn(entity: NpcEntity, level: ServerLevel) {
        val pos = digPos ?: return enterCover(entity) // shouldn't happen, but never get stuck mid-dig
        // Digging takes ~3.5s (DIG_TICKS) — long enough that knockback (an explosion, a shove from
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
            val stage = (9 - 9 * digTicksRemaining / DIG_TICKS).coerceIn(0, 9)
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
        maybeThrowGrenadeOnceDugIn(entity, level)
        enterDugInHolding(entity)
    }

    /** Per user decision: a dug-in mob fights FROM the hole rather than repeating the normal
     *  peek-then-duck-back cycle ([enterCover]/[tickInCover]) — that cycle was built for genuine wall
     *  cover, where popping out to trade shots and ducking back makes sense. Digging only ever
     *  triggers mid-firefight (canDigIn requires an actively-covering ally), so the very next recheck
     *  after finishing would almost always find a live target and immediately walk back out to peek
     *  anyway — reported in-game as "выкопал яму и почти сразу её покинул". Clearing [ModMemories.COVER_HOLD]
     *  immediately hands aiming/firing to [GunAttackBehaviour] as normal; [NpcEntity.diggedIn] is the
     *  new signal that tells it to skip all repositioning (advance/bounding/sidestep) while still
     *  aiming and shooting exactly as it would anywhere else — see that flag's own doc comment. */
    private fun enterDugInHolding(entity: NpcEntity) {
        entity.navigation.stop()
        phase = Phase.DUG_IN_HOLDING
        entity.diggedIn = true
        BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
        phaseUntilTick = entity.tickCount + RECHECK_TICKS
        healthAtLastDigInCheck = entity.health
    }

    /** Holds the mob in its foxhole for as long as it's both still hurt (below [DIG_HEALTH_FRACTION],
     *  the same threshold that let it dig in the first place) and still actively fighting — see
     *  [shouldKeepRunning] for the "healed back up" exit, and below for the "nothing left to fight"
     *  exit. [GunAttackBehaviour] does all the actual aiming/shooting on its own once [ModMemories.COVER_HOLD]
     *  is cleared (see [enterDugInHolding]); this just keeps the suppression memory (and therefore
     *  this whole behaviour, and therefore [NpcEntity.diggedIn]) alive while there's still a live
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
        // still allows it — capped at MAX_DIGS total, never unlimited (see digsUsed's own doc
        // comment). Real cover isn't re-checked here: even if some turned up, nothing currently
        // moves a diggedIn mob out of its hole to use it, so there'd be no alternative action to
        // take on that information anyway.
        val tookFreshHit = entity.health < healthAtLastDigInCheck
        healthAtLastDigInCheck = entity.health
        if (tookFreshHit && digsUsed < MAX_DIGS) {
            val level = entity.level() as? ServerLevel ?: return
            if (canDigIn(entity, level, entity.blockPosition())) {
                startDigging(entity)
            }
        }
    }

    /** Per user request: engaged (has a target) with no real cover nearby and unable/unwilling to
     *  dig any further — hold the current spot and keep firing rather than fleeing across open
     *  ground. Clears [ModMemories.COVER_HOLD] like [enterDugInHolding] so [GunAttackBehaviour] keeps
     *  full control of aim/fire AND its own partial-cover positioning (`holdFiringPosition`) — unlike
     *  actually digging in, [NpcEntity.diggedIn] is deliberately NOT set here, since there's no hole
     *  to stay locked into; GunAttackBehaviour is free to reposition within its own small search
     *  radius exactly as it would with no suppression involved at all. */
    private fun enterHoldingOpen(entity: NpcEntity) {
        entity.navigation.stop()
        phase = Phase.HOLDING_OPEN
        BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
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
        if (digsUsed < MAX_DIGS && canDigIn(entity, level, entity.blockPosition())) {
            startDigging(entity)
        }
    }

    /** Per user decision ("просто проверять попытку выйти"): healed up and ready to stand down, but
     *  a [MAX_DIGS]-deep hole isn't always something a mob can just climb straight out of on its own
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
        // NpcEntity.diggedIn (GunAttackBehaviour first among them) treats it as "hands off this
        // mob's movement" and would otherwise call navigation.stop() on the very same/next tick,
        // canceling the exit attempt before it could ever actually leave.
        entity.diggedIn = false
        val level = entity.level() as? ServerLevel
        val hole = coverTarget
        if (level != null && hole != null) {
            val exitPoint = NEIGHBOR_OFFSETS.map { (dx, dz) -> Terrain.groundAt(level, hole.offset(dx, 0, dz)) }
                .minByOrNull { it.distSqr(hole) }
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
        // navigator has already given up (the expected case for a genuinely MAX_DIGS-deep pit —
        // vanilla mobs auto-step/jump one block, not two), dig through instead of leaving it stuck.
        if (hole != null && entity.navigation.isDone) {
            digSideExit(entity, level, hole)
        }
        duggenSideExit = true
    }

    /** Breaks through the pit wall and physically places the mob back at the surface — see
     *  [hole]'s own two solid layers below. A single sideways block alone doesn't actually reach
     *  daylight from a [MAX_DIGS]-deep pit (PM review finding): the untouched neighbor terrain is
     *  ordinary continuous ground at the SAME depth as the shaft, not a path to the surface — a mob
     *  that steps into a one-block notch punched in that wall is still exactly as deep underground
     *  as before, just sideways. Rather than simulate a full staircase dig for what's meant to be a
     *  simple last-resort escape (per user framing, "просто проверять попытку выйти"), break the
     *  wall for visual/physical consistency with what was asked, then place the mob at the nearest
     *  original-surface point beyond it directly — guarantees it never gets permanently wedged
     *  in [Phase.EXITING_HOLE] (which is exactly what happened before this fix: the block broken
     *  wasn't even a real wall, so [hasClimbedOut] could never trip). */
    private fun digSideExit(entity: NpcEntity, level: ServerLevel, hole: BlockPos) {
        val (dx, dz) = NEIGHBOR_OFFSETS.firstOrNull { (ox, oz) ->
            val exit = hole.offset(ox, 1, oz)
            level.getBlockState(exit).isAir && level.getBlockState(exit.above()).isAir
        } ?: NEIGHBOR_OFFSETS.first()
        // hole.y and hole.y - 1: the two solid layers actually enclosing the mob (hole.y + 1 would be
        // the ORIGINAL surface opening it fell through in the first place — already open, nothing to
        // break there).
        level.destroyBlock(hole.offset(dx, 0, dz), false, entity, 512)
        level.destroyBlock(hole.offset(dx, -1, dz), false, entity, 512)
        // hole.y + 1, NOT a live Terrain.groundAt() query (PM review finding): isFlatEnoughToDig already
        // guaranteed this exact neighbor column's ground sits at or below hole.y when the last dig
        // started (so its own open/walkable height is at or above hole.y + 1) — a real, pre-verified
        // invariant. Querying Terrain.groundAt() here instead would read back the two blocks just destroyed
        // one line above, and/or (at distance 2) terrain that flatness never actually checked —
        // either way risking landing the mob back down inside the breach rather than above it.
        val exitPoint = hole.offset(dx, 1, dz)
        entity.teleportTo(exitPoint.x + 0.5, exitPoint.y.toDouble(), exitPoint.z + 0.5)
    }

    private fun clearDigProgress(entity: NpcEntity) {
        (entity.level() as? ServerLevel)?.destroyBlockProgress(entity.id, digPos ?: return, -1)
    }

    /** Per user request: once actually dug in (not before) — a "so the enemy flinches while I catch
     *  my breath" parting shot, not a pre-emptive one. Small [GRENADE_THROW_CHANCE] roll, needs
     *  a grenade left ([NpcEntity.grenadeToThrow]; every non-crew NpcClass starts with 2+2, see
     *  [NpcEntity.applyRole]) — counted, not a visible held item (user feedback: a physical
     *  offhand grenade looked wrong with a gun already in the main hand). Thrown at [NpcEntity.threatPos] (the suppressing threat, not
     *  necessarily the current `target`) since that's who this is meant to rattle. Same
     *  friendly-fire gates as the ordinary grenade behaviour — skip silently rather than risk
     *  hitting an ally, the grenade just stays in reserve for next time. */
    private fun maybeThrowGrenadeOnceDugIn(entity: NpcEntity, level: ServerLevel) {
        if (!entity.hasGrenade) return
        if (entity.random.nextDouble() >= GRENADE_THROW_CHANCE) return
        val threat = entity.threatPos ?: return
        val grenade = GrenadeThrower.pick(entity, level, threat) ?: return
        GrenadeThrower.throwAt(entity, level, threat, grenade)
    }

    /** Gates digging in to exactly the invariants the user asked for:
     *   1. badly hurt (below [DIG_HEALTH_FRACTION] of max health) — not something a healthy NPC
     *      bothers with;
     *   2. a squadmate is actually covering: nearby and demonstrably engaging, preferring "fired a
     *      shot in the last [COVERING_FIRE_WINDOW_TICKS] ticks" (real, verified suppressing fire —
     *      see [NpcEntity.lastShotTick]) but falling back to "has a live target it can currently see
     *      and isn't itself cover-locked" (clearly engaging, just between shots) if nothing fired
     *      that exact instant;
     *   3. standable dirt- or sand-family ground ([BlockTags.DIRT]/[BlockTags.SAND]) directly
     *      underfoot — sand added per user request ("земля/песок").
     *  Deliberately does NOT check depth of the resulting hole here — see [isFlatEnoughToDig] for
     *  the actual "this would be a real foxhole, not one block broken on a slope" guarantee. */
    private fun canDigIn(entity: NpcEntity, level: ServerLevel, pos: BlockPos): Boolean {
        val hurtEnough = entity.health < entity.maxHealth * DIG_HEALTH_FRACTION
        val belowState = level.getBlockState(pos.below())
        val diggableGround = belowState.`is`(BlockTags.DIRT) || belowState.`is`(BlockTags.SAND)
        // In order of cost; the last two walk the ground and the squad. All four only for the trace.
        if (!DebugFlags.on(LogGroup.DIG)) {
            return hurtEnough && diggableGround && isFlatEnoughToDig(level, pos) && hasCoveringAlly(entity, level)
        }
        val flatEnough = isFlatEnoughToDig(level, pos)
        val covered = hasCoveringAlly(entity, level)
        if (!(hurtEnough && diggableGround && flatEnough && covered)) {
            DebugFlags.log(LogGroup.DIG,
                "{} at {} hurtEnough={} diggableGround={} flatEnough={} covered={}",
                entity.uuid, pos, hurtEnough, diggableGround, flatEnough, covered
            )
        }
        return hurtEnough && diggableGround && flatEnough && covered
    }

    private fun hasCoveringAlly(entity: NpcEntity, level: ServerLevel): Boolean {
        val squad = entity.currentSquad() ?: return false
        NpcRegistry.forEachWithin(level, entity.position(), COVERING_ALLY_RADIUS, exclude = entity) { ally ->
            if (squad.members.contains(ally.uuid) && isActuallyCovering(ally)) return true
        }
        return false
    }

    /** Confirmed via a [dig-debug] log capture in-game: gating on "actively engaging right now" was
     *  the real bottleneck, not health/ground/flatness (those passed every single time). In an open
     *  fight with no cover for anyone, the whole squad tends to get suppressed together — by the
     *  moment someone is hurt badly enough to actually want to dig in, every nearby ally is equally
     *  pinned down too, so nobody ever had a live target + clear LOS at that exact instant. Added a
     *  third, looser tier: an ally who ISN'T suppressed themselves is "in a position to help" even
     *  without proof they're mid-shot right now — still prefers the stronger, verified signals first. */
    private fun isActuallyCovering(ally: NpcEntity): Boolean {
        if (ally.firedRecently(COVERING_FIRE_WINDOW_TICKS)) return true
        val target = ally.target
        if (target != null && target.isAlive && !ally.combatLockedByCover() && ally.sensing.hasLineOfSight(target)) return true
        return !ally.isSuppressed()
    }

    /** [pos] only counts as a real dig-in site if the ground around it is at least as high as
     *  [pos] itself on every side (checked 8-directionally) — i.e. genuinely flat/level terrain, so
     *  the resulting hole is enclosed by the ORIGINAL, undisturbed ground on every side once dug,
     *  not a single block broken on a slope or at the edge of an existing depression (which
     *  wouldn't actually block fire from the low side at all — the user's own example of what
     *  "digging in" must NOT be). */
    private fun isFlatEnoughToDig(level: ServerLevel, pos: BlockPos): Boolean {
        return DIG_NEIGHBOR_OFFSETS.all { (dx, dz) -> Terrain.groundAt(level, pos.offset(dx, 0, dz)).y >= pos.y }
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
        // digsUsed < MAX_DIGS: without this cap, digging one hole and falling into it
        // (blockPosition() drops by one) re-passes every condition of canDigIn at the new, deeper
        // spot — see digsUsed's own doc comment for the endless-shaft bug this caused.
        if (isFallbackRetreat && digsUsed < MAX_DIGS && canDigIn(entity, level, entity.blockPosition())) {
            startDigging(entity)
            return
        }
        val target = entity.target?.takeIf { com.sbwnpc.squad.combat.DetectionSightline.canSee(entity, it) }
        if (target != null && target.isAlive) {
            phase = Phase.PEEKING
            phaseUntilTick = entity.tickCount + PEEK_TICKS
            // Unlock — GunAttackBehaviour (no longer locked out, see combatLockedByCover) takes
            // over aiming/approach/fire from here; this is just enough of a nudge to clear
            // whatever's currently blocking sight from the cover point itself.
            BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
            val peekPoint = coverTarget?.let { findPeekPoint(entity, level, it, target) } ?: target.position()
            entity.navigation.moveTo(peekPoint.x, peekPoint.y, peekPoint.z, 1.0)
        } else {
            val aim = entity.incomingFire.point(level.gameTime)
            val peek = if (aim != null && entity.incomingFire.pending(level.gameTime))
                coverTarget?.let { blindPeekPoint(entity, level, it, aim) } else null
            if (peek != null) {
                phase = Phase.PEEKING
                phaseUntilTick = entity.tickCount + 60
                entity.incomingFire.beginReply(level.gameTime)
                BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
                entity.navigation.moveTo(peek.x, peek.y, peek.z, 1.0)
            } else phaseUntilTick = entity.tickCount + RECHECK_TICKS
        }
    }

    /** Minimal-exposure peek point: steps from [cover] toward [target] by [PEEK_STEP_DISTANCES] in
     *  order, taking the FIRST one with an actual clear raycast to the target's eyes — leaning out
     *  just enough, not a full advance. Falls back to the target's own position (the old behaviour)
     *  if nothing within the tried distances gets a clear shot — GunAttackBehaviour's own
     *  bounding-advance takes over from there exactly as it already did before this change. */
    private fun findPeekPoint(entity: NpcEntity, level: ServerLevel, cover: BlockPos, target: LivingEntity): Vec3 {
        val base = Vec3(cover.x + 0.5, cover.y.toDouble(), cover.z + 0.5)
        val toTarget = target.position().subtract(base)
        val horiz = Vec3(toTarget.x, 0.0, toTarget.z)
        if (horiz.lengthSqr() < 1.0e-6) return target.position()
        val dir = horiz.normalize()
        // A peek is only a peek if it can actually shoot from there — leaning out into the side of
        // a parked vehicle is the same dead end as the cover itself.
        val hulls = Sightline.vehicleHulls(
            level, AABB(base, target.eyePosition).inflate(2.0), entity, target
        )
        for (step in PEEK_STEP_DISTANCES) {
            val candidate = base.add(dir.scale(step))
            val eye = candidate.add(0.0, 1.5, 0.0)
            if (!Sightline.blockedBy(level, eye, target.eyePosition, entity, hulls, entity.spread)) return candidate
        }
        return target.position()
    }

    private fun blindPeekPoint(entity: NpcEntity, level: ServerLevel, cover: BlockPos, aim: Vec3): Vec3? {
        val base = cover.bottomCenter
        val flat = aim.subtract(base).multiply(1.0, 0.0, 1.0).normalize()
        val side = Vec3(-flat.z, 0.0, flat.x)
        val hulls = Sightline.vehicleHulls(level, AABB(base, aim).inflate(3.0), entity, null)
        for (step in PEEK_STEP_DISTANCES) {
            for (direction in listOf(side, side.scale(-1.0), flat)) {
                val candidate = Terrain.standableOrNull(level, base.x + direction.x * step, base.y, base.z + direction.z * step) ?: continue
                if (Sightline.blockedBy(level, candidate.add(0.0, entity.eyeHeight.toDouble(), 0.0), aim, entity, hulls, entity.spread)) continue
                val path = entity.navigation.createPath(candidate.x, candidate.y, candidate.z, 0) ?: continue
                if (path.canReach()) return candidate
            }
        }
        return null
    }

    private fun tickPeeking(entity: NpcEntity) {
        val target = entity.target
        val blindReply = entity.incomingFire.replying(entity.level().gameTime) &&
            (target == null || !com.sbwnpc.squad.combat.DetectionSightline.canSee(entity, target))
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
        entity.incomingFire.endReply()
        phase = Phase.RETURNING_TO_COVER
        BrainUtils.setMemory(entity, ModMemories.COVER_HOLD.get(), true)
        val target = coverTarget ?: return
        entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
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
