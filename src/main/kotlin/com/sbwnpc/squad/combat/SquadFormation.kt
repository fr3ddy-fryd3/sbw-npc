package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * Turns "every squad member walks toward the exact same point" (the literal `home`/route point
 * passed to `navigation.moveTo` in `SquadOrderBehaviour`) into "every squad member walks toward its own
 * slot around that point" — real formation-slot steering (see gdx-ai's Formation Motion, and RTS
 * flocking literature), not a full boids simulation. That single change is what was actually
 * causing both the "everyone piles onto one spot and shoves past each other" pileup and the
 * resulting "snake" of NPCs re-routing around each other every tick.
 *
 * Shape is picked from the squad's current order rather than exposed as a player choice — matches
 * real infantry doctrine roughly (wedge to advance into unclear contact / ATTACK, line abreast to
 * hold a wide front / DEFEND, staggered column to travel a route / PATROL) without needing new UI.
 *
 * All members rotate their slot offset by the SAME heading — member 0 (the squad's "leader" slot)
 * position to the anchor — rather than each mob computing its own facing from its own current
 * position. An earlier version did the latter ("deliberately simple", per its own comment) and it
 * was wrong, not just approximate: with each member using a different heading, the formation had no
 * single consistent orientation at all — every member's wedge/line pointed a slightly different way,
 * which is indistinguishable from random noise once several members are moving at once (reported
 * in-game as "doesn't look like a formation, are you just moving numbers around"). A shared leader
 * heading is what actually makes the shape a shape.
 */
object SquadFormation {

    // 2.5/3.5 read as a blob, 5/6 as a crowd that had lost each other; per user feedback the
    // squad should look like it moves together, so this sits between the two.
    private const val SLOT_SPACING = 3.0
    /** Interval for a squad advancing on an enemy — see GunAttackBehaviour. */
    const val COMBAT_SPACING = 6.0
    private const val RING_RADIUS = 4.0
    private const val MIN_HEADING_LENGTH = 2.0

    // DEFEND, once arrived, is deliberately looser than a held RING perimeter: a garrison holding a
    // position doesn't stand in a neat
    // circle, but shouldn't wander unbounded either. MIN keeps members no closer than the old RING
    // radius; MAX matches SeekCoverBehaviour's own "nearby ally" radius already used elsewhere in
    // this codebase, not an arbitrary new number.
    private const val DEFEND_SCATTER_MIN = RING_RADIUS
    // Inside DEFEND's own arrival radius (ARRIVAL_RADIUS + 4.5), or a member standing on its slot
    // reads as "not arrived" and walks back in.
    private const val DEFEND_SCATTER_MAX = 8.0

    /** Callers that decide "arrived, switch to RING" from raw distance to the anchor MUST use a
     *  threshold at least this big — not RING_RADIUS itself, safely past it. Using anything smaller
     *  (e.g. the ATTACK order's old flat `3.0`, less than RING_RADIUS's `3.5`) is a real bug, not a
     *  tuning nit: a member can satisfy "arrived" while still short of its actual RING slot distance,
     *  get assigned that farther-out RING point, walk toward it, immediately fail "arrived" again
     *  (now farther than the threshold), flip back to the transit shape — whose index-0/leader slot
     *  sits AT the anchor — and walk right back in, repeating forever. That oscillation is exactly
     *  what was reported in-game as "they spread out a little then suddenly all rush right up to the
     *  block, over and over". */
    const val ARRIVAL_RADIUS = RING_RADIUS + 1.5

    private enum class Shape { WEDGE, LINE, COLUMN, GRID, RING, SCATTER }

    /** Transit shape depends on order. MOVE is the exception to the arrival perimeter: it keeps its
     *  ordered infantry grid at the destination. DEFEND arrives into SCATTER rather than RING — see
     *  that shape's own doc note in [localOffset]. */
    private fun shapeFor(order: SquadOrder, arrived: Boolean): Shape {
        // A MOVE command is a formation movement command, including after the destination is
        // reached. Infantry presets map directly to 2x2, 2x4, and 4x4 grids.
        if (order == SquadOrder.MOVE) return Shape.GRID
        if (arrived) return if (order == SquadOrder.DEFEND || order == SquadOrder.BARRAGE) Shape.SCATTER else Shape.RING
        return when (order) {
            SquadOrder.ATTACK -> Shape.WEDGE
            SquadOrder.DEFEND, SquadOrder.BARRAGE -> Shape.LINE
            SquadOrder.PATROL -> Shape.COLUMN
            SquadOrder.MOVE -> Shape.GRID
        }
    }

    /** Local (unrotated, +Z = forward/toward anchor) offset for the [slotIndex]-th member out of
     *  [squadSize]. WEDGE, LINE, and COLUMN use a point/leader slot at the anchor. GRID, RING, and
     *  SCATTER give every member its own point. */
    private fun localOffset(shape: Shape, slotIndex: Int, squadSize: Int, spacing: Double = SLOT_SPACING): Vec3 {
        if (shape == Shape.RING) {
            val count = squadSize.coerceAtLeast(1)
            val angle = 2.0 * Math.PI * slotIndex / count
            return Vec3(kotlin.math.sin(angle) * RING_RADIUS, 0.0, kotlin.math.cos(angle) * RING_RADIUS)
        }
        if (shape == Shape.SCATTER) {
            // Stable PER-SLOT pseudo-random point (same seed -> same angle/distance every tick, no
            // drift) rather than a neat evenly-spaced ring for a garrison holding ground rather
            // than a hasty perimeter.
            // slotTarget() does NOT rotate this by the shared heading (nor RING's own angle above) —
            // an earlier version of this comment claimed that rotation was merely a "harmless no-op"
            // for both shapes since a full-circle distribution is rotation-symmetric either way, but
            // that reasoning only holds for the SET of all slots, not for one specific slot tracked
            // over time: rotating by an unstable heading (DEFEND's own "leader" slot is itself
            // SCATTER-shaped and drifting, never fixed) made that slot's target point continuously
            // orbit the anchor — reported in-game as a defending squad visibly circling its own
            // point. See slotTarget()'s own comment for the actual fix.
            val rnd = java.util.Random(slotIndex.toLong() * 2654435761L)
            val angle = rnd.nextDouble() * Math.PI * 2
            val dist = DEFEND_SCATTER_MIN + rnd.nextDouble() * (DEFEND_SCATTER_MAX - DEFEND_SCATTER_MIN)
            return Vec3(kotlin.math.sin(angle) * dist, 0.0, kotlin.math.cos(angle) * dist)
        }
        if (shape == Shape.GRID) {
            val (columns, rows) = when {
                squadSize <= 4 -> 2 to 2
                squadSize <= 8 -> 2 to 4
                else -> 4 to 4
            }
            val column = slotIndex % columns
            val row = slotIndex / columns
            return Vec3(
                (column - (columns - 1) / 2.0) * spacing,
                0.0,
                ((rows - 1) / 2.0 - row) * spacing
            )
        }
        if (slotIndex <= 0) return Vec3.ZERO
        val rank = (slotIndex + 1) / 2
        val side = if (slotIndex % 2 == 1) -1.0 else 1.0
        return when (shape) {
            // Spreads out sideways AND drops back per rank — the classic V/wedge shape.
            Shape.WEDGE -> Vec3(side * rank * spacing, 0.0, -rank * spacing)
            // Spreads sideways only, same depth as the leader — a wide front.
            Shape.LINE -> Vec3(side * rank * spacing, 0.0, 0.0)
            // Mostly single-file, alternating slightly left/right (staggered column) rather than
            // dead in the last member's footsteps.
            Shape.COLUMN -> Vec3(side * spacing * 0.4, 0.0, -rank * spacing)
            Shape.GRID, Shape.RING, Shape.SCATTER -> Vec3.ZERO // unreachable, handled above
        }
    }

    /** World-space point [mob] should path toward instead of the bare [anchor] — offset by its
     *  formation slot, rotated toward the squad's shared heading (ignored for RING/SCATTER, both
     *  rotation-symmetric by construction). [fallbackFacing] is only used when the leader itself can't supply
     *  a heading (dead/unloaded, or IS the mob asking) — see [headingFor]. [arrived] switches the
     *  shape to RING regardless of order — see [shapeFor]. Falls back to [anchor] itself if [mob]
     *  isn't actually in a squad (shouldn't happen for real callers, but cheap to guard). */
    fun slotTarget(
        mob: NpcEntity, anchor: Vec3, fallbackFacing: Vec3, arrived: Boolean, spacing: Double = SLOT_SPACING
    ): Vec3 {
        val squad = mob.currentSquad() ?: return anchor
        val index = mob.slotIndex(squad)
        if (index < 0) return anchor
        val shape = shapeFor(squad.order, arrived)
        val local = localOffset(shape, index, squad.members.size, spacing)
        if (local == Vec3.ZERO) return anchor

        // The doc comment above already claimed this ("ignored for RING/SCATTER, both
        // rotation-symmetric by construction"), but the code never actually skipped the rotation
        // below for them — a real bug, not just a stale comment. RING/SCATTER already assign each
        // slot its own angle across the FULL circle (see localOffset), so rotating that offset by
        // a heading vector adds nothing to the overall distribution — but for one SPECIFIC slot
        // tracked over time, it makes that slot's target point continuously rotate around the
        // anchor whenever the heading itself isn't perfectly still. For DEFEND's SCATTER the
        // heading never is: it's anchor-minus-leader's-position, and the "leader" reference member
        // is itself scattered and drifting within its own 6-16 block band, never fixed. Every other
        // member then chases a continuously rotating target — reported in-game as a defending
        // squad "водит хоровод" (circling its own defend point) even with no target/no attack in
        // progress at all.
        if (shape == Shape.RING || shape == Shape.SCATTER) return anchor.add(local)

        val heading = headingFor(mob, anchor, fallbackFacing)
        val flat = Vec3(heading.x, 0.0, heading.z)
        // Threshold is deliberately much bigger than "exactly zero": a leader settled within its own
        // ~1.5-block "close enough, stop navigating" radius of the anchor (routine once arrived, or
        // for the leader's own non-RING slot which sits AT the anchor while still in transit) still
        // produces a heading vector short enough that ordinary per-tick position jitter swings its
        // DIRECTION wildly — the follower(s) then chase that spinning direction, which is exactly
        // what was reported in-game as small squads "circling" close together. A real terrain bump
        // or pathing correction moves a settled mob by inches, not blocks, so a vector shorter than
        // MIN_HEADING_LENGTH is noise, not a meaningful heading — fall back to the unrotated offset
        // instead of rotating by direction that isn't actually stable tick to tick.
        if (flat.lengthSqr() < MIN_HEADING_LENGTH * MIN_HEADING_LENGTH) return anchor.add(local)
        val fwd = flat.normalize()
        val right = Vec3(-fwd.z, 0.0, fwd.x)
        return anchor.add(fwd.scale(local.z)).add(right.scale(local.x))
    }

    /** Stable MOVE-grid slot with a caller-supplied heading. Unlike [slotTarget], this never derives
     *  its direction from a settling leader, so rallying and holding a grid cannot rotate in place. */
    fun moveSlotTarget(mob: NpcEntity, anchor: Vec3, heading: Vec3): Vec3 {
        val squad = mob.currentSquad() ?: return anchor
        val index = mob.slotIndex(squad)
        if (index < 0) return anchor
        val local = localOffset(Shape.GRID, index, squad.members.size)
        val flat = Vec3(heading.x, 0.0, heading.z)
        if (flat.lengthSqr() < MIN_HEADING_LENGTH * MIN_HEADING_LENGTH) return anchor.add(local)
        val forward = flat.normalize()
        val right = Vec3(-forward.z, 0.0, forward.x)
        return anchor.add(forward.scale(local.z)).add(right.scale(local.x))
    }

    /** One heading shared by every member of [mob]'s squad this tick: anchor minus the position of
     *  squad member 0 (the leader slot), so the whole formation is consistently oriented no matter
     *  which member is asking. Falls back to [fallbackFacing] (the caller's own anchor-relative
     *  vector) if the leader is dead/unloaded, or if [mob] itself IS the leader (nothing else to
     *  reference — its own approach vector is the best available heading in that case). */
    private fun headingFor(mob: NpcEntity, anchor: Vec3, fallbackFacing: Vec3): Vec3 {
        val squad = mob.currentSquad() ?: return fallbackFacing
        val leaderId = squad.members.firstOrNull() ?: return fallbackFacing
        if (leaderId == mob.uuid) return fallbackFacing
        val leader = (mob.level() as? ServerLevel)?.getEntity(leaderId) ?: return fallbackFacing
        if (!leader.isAlive) return fallbackFacing
        return anchor.subtract(leader.position())
    }
}
