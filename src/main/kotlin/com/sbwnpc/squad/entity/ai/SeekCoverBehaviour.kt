package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
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
 *
 * Digging in (feature/dig-in): if [findCover] finds no real cover and the mob has to fall back to
 * [fallbackAwayFrom] instead, once it arrives there it may dig itself a foxhole in place — see
 * [canDigIn] for the exact gating (badly hurt, a squadmate actually covering it, standable dirt,
 * flat enough ground) and [tickDiggingIn]/[finishDigging] for the dig itself. Deliberately gated
 * behind the fallback path only, per user instruction — a squad that found genuine cover has no
 * need to dig, and digging should never preempt or delay reaching real cover.
 *
 * Peeking is a minimal-exposure lean, not a full walk-out (see [findPeekPoint]): tries a handful of
 * short steps toward the target (0.5 up to 3 blocks) and takes the first one with a clear line of
 * sight, rather than always closing all the way to the target's own position. This is the standard
 * "smart cover point" idiom from tactical-shooter AI (F.E.A.R.'s cover/lean system is the usual
 * reference: Jeff Orkin, "Three States and a Plan: The AI of F.E.A.R.", GDC 2006) — expose as
 * little of yourself as the terrain actually requires to get a shot, then duck straight back.
 */
class SeekCoverBehaviour : ExtendedBehaviour<NpcEntity>() {

    private enum class Phase { MOVING_TO_COVER, DIGGING_IN, IN_COVER, PEEKING, RETURNING_TO_COVER }

    private var phase = Phase.MOVING_TO_COVER
    private var coverTarget: BlockPos? = null
    private var phaseUntilTick = 0
    private var isFallbackRetreat = false // coverTarget came from fallbackAwayFrom, not findCover
    private var digTicksRemaining = 0
    private var digPos: BlockPos? = null // block being dug, tracked separately for the progress overlay

    companion object {
        private const val SAMPLE_COUNT = 20
        private const val MIN_RADIUS = 5.0
        private const val MAX_RADIUS = 28.0        // x2 per user request
        private const val WALL_SCAN_STEP = 2       // grid spacing (blocks) for the wall-adjacency scan
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

        private const val DIG_HEALTH_FRACTION = 0.5f  // only badly hurt NPCs bother digging in
        private const val DIG_TICKS = 70              // ~3.5s of "digging" before the hole is done
        private const val COVERING_ALLY_RADIUS = 16.0
        private const val COVERING_FIRE_WINDOW_TICKS = 40 // ~2s — covers gaps between shots, not just a single tick

        // Debug-visual colors (see markCoverChoice) — GREEN for a real found-cover spot, ORANGE for
        // a blind fallback retreat, BROWN for an actually-started dig.
        private val GREEN = org.joml.Vector3f(0.2f, 1.0f, 0.2f)
        private val ORANGE = org.joml.Vector3f(1.0f, 0.6f, 0.0f)
        private val BROWN = org.joml.Vector3f(0.55f, 0.35f, 0.1f)

        private val NEIGHBOR_OFFSETS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        // How far out (in block-widths, along each NEIGHBOR_OFFSETS direction) to look for a
        // depression's actual rim — see hasAdjacentSolidWall's doc comment.
        private val DEPRESSION_CHECK_DISTANCES = 1..3
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
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = entity.isSuppressed()

    override fun start(entity: NpcEntity) {
        // TEMPORARY diagnostic, round 3 — user reports the mob still re-enters fallback retreat
        // even though suppression visibly gets refreshed. Verified via javap on the real
        // SmartBrainLib jar that ONCE RUNNING, a behaviour can ONLY be stopped by
        // shouldKeepRunning()==false (isSuppressed()) or an (unused, default-inert) stopCondition —
        // getMemoryRequirements()/hasRequiredMemories() only gates the INITIAL start, never
        // continuation — so on paper this start() should only fire once per genuinely fresh
        // suppression episode. Logging every call to find out whether that's actually true.
        com.sbwnpc.squad.SquadMod.LOGGER.info("[dig-debug] {} start() at tick {}", entity.uuid, entity.tickCount)
        phase = Phase.MOVING_TO_COVER
        coverTarget = null
        phaseUntilTick = 0
        isFallbackRetreat = false
        digTicksRemaining = 0
        digPos = null
        BrainUtils.setMemory(entity, ModMemories.COVER_HOLD.get(), true)
    }

    override fun stop(entity: NpcEntity) {
        // TEMPORARY diagnostic, round 3 — same theory as before, now also logging the tick number
        // to correlate precisely against the [dig-debug] refresh logs in startDigging()/enterCover().
        if (isFallbackRetreat) {
            com.sbwnpc.squad.SquadMod.LOGGER.info(
                "[dig-debug] {} stop() at tick {} in phase {} (fallback)", entity.uuid, entity.tickCount, phase
            )
        }
        coverTarget = null
        entity.navigation.stop()
        digPos?.let { clearDigProgress(entity) }
        BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
    }

    override fun tick(entity: NpcEntity) {
        val threat = entity.threatPos ?: return
        val level = entity.level() as? ServerLevel ?: return

        when (phase) {
            Phase.MOVING_TO_COVER -> tickMovingToCover(entity, level, threat)
            Phase.DIGGING_IN -> tickDiggingIn(entity, level)
            Phase.IN_COVER -> tickInCover(entity, level)
            Phase.PEEKING -> tickPeeking(entity)
            Phase.RETURNING_TO_COVER -> tickReturningToCover(entity)
        }
    }

    private fun tickMovingToCover(entity: NpcEntity, level: ServerLevel, threat: Vec3) {
        val target = coverTarget
        if (target != null) {
            if (entity.position().closerThan(target.center, 1.5)) {
                if (isFallbackRetreat && canDigIn(entity, level, target)) {
                    startDigging(entity, level, target)
                } else {
                    enterCover(entity, refreshSuppression = true)
                }
            }
            return // still travelling this leg either way
        }
        findCover(entity, level, threat)?.let {
            isFallbackRetreat = false
            coverTarget = it
            entity.navigation.moveTo(it.x + 0.5, it.y.toDouble(), it.z + 0.5, 1.0)
            markCoverChoice(level, it, GREEN)
            return
        }
        fallbackAwayFrom(entity, threat)?.let {
            isFallbackRetreat = true
            coverTarget = it
            entity.navigation.moveTo(it.x + 0.5, it.y.toDouble(), it.z + 0.5, 1.0)
            markCoverChoice(level, it, ORANGE)
            // TEMPORARY diagnostic, round 2 — user reports still nobody digging in after the
            // covering-ally check was loosened. Confirms whether NPCs even reach the fallback path
            // at all in this test (vs. findCover succeeding often enough that fallback is rare).
            com.sbwnpc.squad.SquadMod.LOGGER.info("[dig-debug] {} entered fallback retreat", entity.uuid)
        }
    }

    /** Debug visual, per user request ("посмотреть что именно они выбирают укрытием") — a short
     *  particle burst at the exact block chosen as cover, GREEN for a real [findCover] hit (a wall
     *  the raycast actually verified blocks the threat), ORANGE for a [fallbackAwayFrom] retreat
     *  (no real cover found nearby at all). Visible to every nearby player, not just the squad's
     *  owner — this is a diagnostic aid, not a player-facing HUD marker like
     *  `SquadManager.setObjective`'s particles. */
    private fun markCoverChoice(level: ServerLevel, pos: BlockPos, color: org.joml.Vector3f) {
        level.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(color, 1.5f),
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 12, 0.3, 0.3, 0.3, 0.0
        )
    }

    /** [refreshSuppression] must be true only for a mob's FIRST settle into cover this episode (a
     *  fresh [findCover] arrival, or right after [finishDigging]) — see those call sites — never for
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
        if (refreshSuppression) {
            entity.threatPos?.let { entity.suppress(it) }
            // TEMPORARY diagnostic, round 4 — round 3's log showed a refresh at tick 499
            // (phaseUntilTick=530) followed by stop() at tick 539, only ~40 ticks later — nowhere
            // near suppress()'s claimed 100-tick floor. Logging the actual raw remaining-tick value
            // straight from BrainUtils right after the refresh, to see whether suppress() itself is
            // computing a too-small number or whether the memory decays faster than expected
            // afterward.
            val rawRemaining = BrainUtils.getTimeUntilMemoryExpires(entity, ModMemories.SUPPRESSING_THREAT.get())
            com.sbwnpc.squad.SquadMod.LOGGER.info(
                "[dig-debug] {} enterCover refreshed suppression at tick {}, rawRemaining={}, isSuppressed={}, phaseUntilTick={}",
                entity.uuid, entity.tickCount, rawRemaining, entity.isSuppressed(), phaseUntilTick
            )
        }
    }

    private fun startDigging(entity: NpcEntity, level: ServerLevel, target: BlockPos) {
        entity.navigation.stop()
        phase = Phase.DIGGING_IN
        digTicksRemaining = DIG_TICKS
        digPos = target.below()
        markCoverChoice(level, target, BROWN)
        // Refresh suppression right as digging starts — reported in-game as "digs in, then
        // immediately runs off": DIG_TICKS (~3.5s) can eat most of a suppression window (5-10s)
        // that was already partway through when digging began, so `isSuppressed()` could lapse
        // right as/after the hole finishes, which stops this whole behaviour (see stop()) and
        // clears COVER_HOLD — unlocking GunAttackBehaviour to immediately march the mob back out
        // toward its target before it ever got to actually use the cover it just dug. `suppress()`
        // extends to at least its own fixed 100-tick minimum (see NpcEntity), comfortably covering
        // the dig plus initial settle — same mechanism a fresh hit would use, not a new one.
        entity.threatPos?.let { entity.suppress(it) }
        // TEMPORARY diagnostic, round 4 — same reason as enterCover()'s log.
        val rawRemaining = BrainUtils.getTimeUntilMemoryExpires(entity, ModMemories.SUPPRESSING_THREAT.get())
        com.sbwnpc.squad.SquadMod.LOGGER.info(
            "[dig-debug] {} startDigging refreshed suppression at tick {}, rawRemaining={}, isSuppressed={}",
            entity.uuid, entity.tickCount, rawRemaining, entity.isSuppressed()
        )
    }

    private fun tickDiggingIn(entity: NpcEntity, level: ServerLevel) {
        val pos = digPos ?: return enterCover(entity) // shouldn't happen, but never get stuck mid-dig
        digTicksRemaining--
        if (digTicksRemaining <= 0) {
            finishDigging(entity, level)
        } else {
            val stage = (9 - 9 * digTicksRemaining / DIG_TICKS).coerceIn(0, 9)
            level.destroyBlockProgress(entity.id, pos, stage)
        }
    }

    private fun finishDigging(entity: NpcEntity, level: ServerLevel) {
        val pos = digPos ?: return
        clearDigProgress(entity)
        level.destroyBlock(pos, false, entity, 512)
        digPos = null
        enterCover(entity, refreshSuppression = true)
    }

    private fun clearDigProgress(entity: NpcEntity) {
        (entity.level() as? ServerLevel)?.destroyBlockProgress(entity.id, digPos ?: return, -1)
    }

    /** Gates digging in to exactly the invariants the user asked for:
     *   1. badly hurt (below [DIG_HEALTH_FRACTION] of max health) — not something a healthy NPC
     *      bothers with;
     *   2. a squadmate is actually covering: nearby and demonstrably engaging, preferring "fired a
     *      shot in the last [COVERING_FIRE_WINDOW_TICKS] ticks" (real, verified suppressing fire —
     *      see [NpcEntity.lastShotTick]) but falling back to "has a live target it can currently see
     *      and isn't itself cover-locked" (clearly engaging, just between shots) if nothing fired
     *      that exact instant;
     *   3. standable dirt-family ground ([BlockTags.DIRT]) directly underfoot.
     *  Deliberately does NOT check depth of the resulting hole here — see [isFlatEnoughToDig] for
     *  the actual "this would be a real foxhole, not one block broken on a slope" guarantee. */
    private fun canDigIn(entity: NpcEntity, level: ServerLevel, pos: BlockPos): Boolean {
        val hurtEnough = entity.health < entity.maxHealth * DIG_HEALTH_FRACTION
        val diggableGround = level.getBlockState(pos.below()).`is`(BlockTags.DIRT)
        val flatEnough = isFlatEnoughToDig(level, pos)
        val covered = hasCoveringAlly(entity, level)
        // TEMPORARY diagnostic, round 2 (see the fallback-retreat log above) — round 1 fixed the
        // covering-ally check based on this same log, but the user reports still nobody digging in.
        // Also checked periodically now (tickInCover), not just once at arrival — see that method.
        if (!(hurtEnough && diggableGround && flatEnough && covered)) {
            com.sbwnpc.squad.SquadMod.LOGGER.info(
                "[dig-debug] {} at {} hurtEnough={} diggableGround={} flatEnough={} covered={}",
                entity.uuid, pos, hurtEnough, diggableGround, flatEnough, covered
            )
        }
        return hurtEnough && diggableGround && flatEnough && covered
    }

    private fun hasCoveringAlly(entity: NpcEntity, level: ServerLevel): Boolean {
        val squad = entity.currentSquad() ?: return false
        val box = AABB.ofSize(entity.position(), COVERING_ALLY_RADIUS * 2, COVERING_ALLY_RADIUS * 2, COVERING_ALLY_RADIUS * 2)
        return level.getEntitiesOfClass(NpcEntity::class.java, box).any { ally ->
            ally !== entity && squad.members.contains(ally.uuid) && isActuallyCovering(ally)
        }
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
        return DIG_NEIGHBOR_OFFSETS.all { (dx, dz) -> groundAt(level, pos.offset(dx, 0, dz)).y >= pos.y }
    }

    private fun tickInCover(entity: NpcEntity, level: ServerLevel) {
        if (entity.tickCount < phaseUntilTick) return
        // Re-check dig-in eligibility periodically while sitting at a fallback (not-real-cover)
        // spot — canDigIn used to only ever get checked ONCE, at the exact tick of arrival. A mob
        // that arrived still above the health threshold (or without a covering ally yet) would
        // never dig in later even after taking more fire while just standing there exposed — this
        // closes that gap, at the same cadence as the ordinary recheck/dwell cycle.
        val pos = coverTarget
        if (isFallbackRetreat && pos != null && canDigIn(entity, level, pos)) {
            startDigging(entity, level, pos)
            return
        }
        val target = entity.target
        if (target != null && target.isAlive) {
            phase = Phase.PEEKING
            phaseUntilTick = entity.tickCount + PEEK_TICKS
            // Unlock — GunAttackBehaviour (no longer locked out, see combatLockedByCover) takes
            // over aiming/approach/fire from here; this is just enough of a nudge to clear
            // whatever's currently blocking sight from the cover point itself.
            BrainUtils.clearMemory(entity, ModMemories.COVER_HOLD.get())
            val peekPoint = pos?.let { findPeekPoint(entity, level, it, target) } ?: target.position()
            entity.navigation.moveTo(peekPoint.x, peekPoint.y, peekPoint.z, 1.0)
        } else {
            // Nothing to shoot at yet — stay down, recheck shortly rather than popping out blind.
            phaseUntilTick = entity.tickCount + RECHECK_TICKS
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
        for (step in PEEK_STEP_DISTANCES) {
            val candidate = base.add(dir.scale(step))
            val eye = candidate.add(0.0, 1.5, 0.0)
            val hit = level.clip(ClipContext(eye, target.eyePosition, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
            if (hit.type != HitResult.Type.BLOCK) return candidate
        }
        return target.position()
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

    /** Cheap proxy for "there's a wall/corner here", biased two ways rather than just one — per
     *  user request, a candidate standing AT the base of a rise counts (a solid block right beside
     *  it at body/head height, same "not air = solid enough" heuristic [groundAt] already uses), but
     *  so does a candidate sitting IN a natural depression/pit whose own rim is higher than the
     *  candidate itself — a dip's edge blocks a ground-level threat's sightline just as well as a
     *  standing wall does, even with nothing solid immediately at the candidate's own body height.
     *  The depression check scans out to [DEPRESSION_CHECK_DISTANCES] blocks, not just the
     *  immediate neighbor — a real dip/trench is normally wider than 1 block, so its actual rim
     *  sits further out than the candidate's own floor, which is still low ground at distance 1 and
     *  never trips the immediate-neighbor check on its own (this was reported in-game as
     *  depressions still not being recognized after the first attempt at this fix). Doesn't need to
     *  know which side the threat is on: [isHiddenFrom]'s raycast is what actually decides whether
     *  this particular wall/rim blocks THIS particular threat. */
    private fun hasAdjacentSolidWall(level: ServerLevel, pos: BlockPos): Boolean {
        val elevatedWallNearby = NEIGHBOR_OFFSETS.any { (nx, nz) ->
            val side = pos.offset(nx, 0, nz)
            !level.getBlockState(side).isAir || !level.getBlockState(side.above()).isAir
        }
        if (elevatedWallNearby) return true
        return DEPRESSION_CHECK_DISTANCES.any { dist ->
            NEIGHBOR_OFFSETS.any { (nx, nz) -> groundAt(level, pos.offset(nx * dist, 0, nz * dist)).y > pos.y }
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
