package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.phys.Vec3

/**
 * The physical steps of taking cover, shared by whoever decides to take them: the cover behaviour
 * for the roles the planner does not cover yet, and the plan's own cover executors (#251). Nothing
 * here decides whether to take cover, dig or fall back; it answers where, whether the ground allows
 * it, and carries the step out.
 */
object CoverMechanics {
    const val FALLBACK_DISTANCE = 6.0
    const val FALLBACK_SPREAD_RADIANS = Math.PI / 3.0 // +/- 60 deg off dead-away-from-threat
    const val DIG_HEALTH_FRACTION = 0.25f // only badly hurt NPCs bother digging in (was 0.5, per user request)
    const val DIG_TICKS = 70 // ~3.5s of "digging" before the hole is done

    // One initial dig, plus at most one extra block deeper if hit again while dug in and still
    // no real cover — per user request. NOT unlimited: a fresh hole drops the man a block and every
    // dig condition can hold again at the new depth, an endless shaft in-game. The plan's methods
    // read the same cap.
    const val MAX_DIGS = com.sbwnpc.squad.ai.tactics.UnderFire.MAX_DIGS
    const val COVERING_ALLY_RADIUS = 16.0
    const val COVERING_FIRE_WINDOW_TICKS = 40 // ~2s — covers gaps between shots, not just a single tick

    // See maybeThrowGrenadeOnceDugIn().
    const val GRENADE_THROW_CHANCE = 0.2

    // Debug-visual colors (see markCoverChoice) — GREEN for a real found-cover spot, ORANGE for
    // a blind fallback retreat, BROWN for an actually-started dig. Gated on DebugFlags.markersEnabled.
    val GREEN = org.joml.Vector3f(0.2f, 1.0f, 0.2f)
    val ORANGE = org.joml.Vector3f(1.0f, 0.6f, 0.0f)
    val BROWN = org.joml.Vector3f(0.55f, 0.35f, 0.1f)

    private val NEIGHBOR_OFFSETS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    // 8-directional (incl. diagonals) — used to confirm the ground is actually flat around the
    // dig site, not just "hidden", see isFlatEnoughToDig().
    private val DIG_NEIGHBOR_OFFSETS = listOf(
        1 to 0,
        -1 to 0,
        0 to 1,
        0 to -1,
        1 to 1,
        1 to -1,
        -1 to 1,
        -1 to -1,
    )

    /** Player-facing visual, per user request — a short particle burst at the exact block chosen as
     *  cover, GREEN for a real [CoverSearch.find] hit (a wall the raycast actually verified blocks the
     *  threat), ORANGE for a [fallbackAwayFrom] retreat (no real cover found nearby at all), BROWN
     *  for an actually-started dig. Gated on [DebugFlags.markersEnabled] — one place to toggle these
     *  off, since the user expects to do that fairly often (e.g. playing with a friend). */
    fun markCoverChoice(level: ServerLevel, pos: BlockPos, color: org.joml.Vector3f) {
        if (!DebugFlags.markersEnabled) return
        level.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(color, 1.5f),
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 12, 0.3, 0.3, 0.3, 0.0,
        )
    }

    /** Per user request: once actually dug in (not before) — a "so the enemy flinches while I catch
     *  my breath" parting shot, not a pre-emptive one. Small [GRENADE_THROW_CHANCE] roll, needs
     *  a grenade left ([NpcEntity.grenadeToThrow]; every non-crew NpcClass starts with 2+2, see
     *  [NpcEntity.applyRole]) — counted, not a visible held item (user feedback: a physical
     *  offhand grenade looked wrong with a gun already in the main hand). Thrown at [NpcEntity.threatPos] (the suppressing threat, not
     *  necessarily the current `target`) since that's who this is meant to rattle. Same
     *  friendly-fire gates as the ordinary grenade behaviour — skip silently rather than risk
     *  hitting an ally, the grenade just stays in reserve for next time. */
    fun maybeThrowGrenadeOnceDugIn(entity: NpcEntity, level: ServerLevel) {
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
    fun canDigIn(entity: NpcEntity, level: ServerLevel, pos: BlockPos): Boolean {
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
            DebugFlags.log(
                LogGroup.DIG,
                "{} at {} hurtEnough={} diggableGround={} flatEnough={} covered={}",
                entity.uuid,
                pos,
                hurtEnough,
                diggableGround,
                flatEnough,
                covered,
            )
        }
        return hurtEnough && diggableGround && flatEnough && covered
    }

    fun hasCoveringAlly(entity: NpcEntity, level: ServerLevel): Boolean {
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
        val shootable = target?.takeIf { it.isAlive && !ally.holdsAny(NpcActivity.COVER, NpcActivity.EVADE) }
        if (shootable != null && ally.sensing.hasLineOfSight(shootable)) return true
        return !ally.isSuppressed()
    }

    /** [pos] only counts as a real dig-in site if the ground around it is at least as high as
     *  [pos] itself on every side (checked 8-directionally) — i.e. genuinely flat/level terrain, so
     *  the resulting hole is enclosed by the ORIGINAL, undisturbed ground on every side once dug,
     *  not a single block broken on a slope or at the edge of an existing depression (which
     *  wouldn't actually block fire from the low side at all — the user's own example of what
     *  "digging in" must NOT be). */
    fun isFlatEnoughToDig(level: ServerLevel, pos: BlockPos): Boolean = DIG_NEIGHBOR_OFFSETS.all { (dx, dz) -> Terrain.groundAt(level, pos.offset(dx, 0, dz)).y >= pos.y }

    /** Breaks through the pit wall and physically places the mob back at the surface — see
     *  [hole]'s own two solid layers below. A single sideways block alone doesn't actually reach
     *  daylight from a MAX_DIGS-deep pit (PM review finding): the untouched neighbor terrain is
     *  ordinary continuous ground at the SAME depth as the shaft, not a path to the surface — a mob
     *  that steps into a one-block notch punched in that wall is still exactly as deep underground
     *  as before, just sideways. Rather than simulate a full staircase dig for what's meant to be a
     *  simple last-resort escape (per user framing, "просто проверять попытку выйти"), break the
     *  wall for visual/physical consistency with what was asked, then place the mob at the nearest
     *  original-surface point beyond it directly — guarantees it never gets permanently wedged
     *  in the exiting phase (which is exactly what happened before this fix: the block broken
     *  wasn't even a real wall, so the climb-out check could never trip). */
    fun digSideExit(entity: NpcEntity, level: ServerLevel, hole: BlockPos) {
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

    /** The ground beside [hole] nearest it, where a man climbing out walks to first. */
    fun holeExit(level: ServerLevel, hole: BlockPos): BlockPos? = NEIGHBOR_OFFSETS.map { (dx, dz) -> Terrain.groundAt(level, hole.offset(dx, 0, dz)) }.minByOrNull { it.distSqr(hole) }

    /** No reachable cover found — put real distance between the mob and the threat instead of
     *  standing still under fire, angled off dead-away by a random spread so several suppressed
     *  squadmates retreating at once don't all bunch up on the same line behind the threat (which
     *  would just recreate the "everyone stacks together" problem this whole feature exists to
     *  avoid, one step removed). */
    fun fallbackAwayFrom(entity: NpcEntity, threat: Vec3, failed: Set<BlockPos>): BlockPos? {
        val away = entity.position().subtract(threat)
        if (away.lengthSqr() < 1.0e-6) return null
        val baseAngle = Math.atan2(away.z, away.x)
        val angle = baseAngle + (entity.random.nextDouble() * 2.0 - 1.0) * FALLBACK_SPREAD_RADIANS
        val dir = Vec3(Math.cos(angle), 0.0, Math.sin(angle))
        val level = entity.level() as? ServerLevel ?: return null
        val feet = Terrain.feetAt(level, entity.position().add(dir.x * FALLBACK_DISTANCE, 0.0, dir.z * FALLBACK_DISTANCE)) {
            level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(it))
        } ?: return null
        return BlockPos.containing(feet.x, kotlin.math.ceil(feet.y), feet.z).takeIf { it !in failed }
    }
}
