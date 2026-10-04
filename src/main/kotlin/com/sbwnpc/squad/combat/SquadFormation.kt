package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
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

    // A mortar crew at its tube or a squad that has just fallen back stands about loosely rather
    // than in a neat circle. MIN keeps members no closer than the RING radius; MAX stays inside the
    // arrival radius (ARRIVAL_RADIUS + 4.5), or a member standing on its slot reads as "not
    // arrived" and walks back in.
    private const val DEFEND_SCATTER_MIN = RING_RADIUS
    private const val DEFEND_SCATTER_MAX = 8.0

    /** Callers that decide "arrived, switch to RING" from raw distance to the anchor MUST use a
     *  threshold at least this big — not RING_RADIUS itself, safely past it. Using anything smaller
     *  (e.g. a flat `3.0`, less than RING_RADIUS) is a real bug, not a
     *  tuning nit: a member can satisfy "arrived" while still short of its actual RING slot distance,
     *  get assigned that farther-out RING point, walk toward it, immediately fail "arrived" again
     *  (now farther than the threshold), flip back to the transit shape — whose index-0/leader slot
     *  sits AT the anchor — and walk right back in, repeating forever. That oscillation is exactly
     *  what was reported in-game as "they spread out a little then suddenly all rush right up to the
     *  block, over and over". */
    const val ARRIVAL_RADIUS = RING_RADIUS + 1.5

    /** Close enough to a point to count as there, whatever the squad's size. */
    private const val NEAR_POINT = ARRIVAL_RADIUS + 4.0

    /**
     * Whether [member] of a squad of [squadSize] has reached [point] for the squad's purposes: near
     * it, or stopped in its place in the formation around it. A big squad's wedge or line puts its
     * outer men twenty and thirty blocks from the point itself — a sixteen-man squad in a wedge had
     * five men "there" and never switched to defending.
     */
    fun reachedPoint(member: NpcEntity, point: Vec3, squadSize: Int): Boolean {
        val d = member.position().distanceTo(point)
        return d <= NEAR_POINT || (member.navigation.isDone && d <= NEAR_POINT + (squadSize / 2) * SLOT_SPACING * 1.5)
    }

    private enum class Shape { WEDGE, LINE, COLUMN, GRID, RING, PERIMETER, SCATTER }

    /**
     * Radius of a defending squad's ring: wide enough that its men stand apart and cover the
     * ground round the point, from [PERIMETER_MIN] for a pair up to [PERIMETER_MAX] at sixteen.
     */
    fun perimeterRadius(squadSize: Int): Double =
        (PERIMETER_MIN + (squadSize - 2) * (PERIMETER_MAX - PERIMETER_MIN) / 14.0).coerceIn(PERIMETER_MIN, PERIMETER_MAX)

    /** How near its point a defender counts as there and takes up its post on the ring. */
    fun defendArrivalRadius(squadSize: Int): Double = perimeterRadius(squadSize) + 4.5

    private const val PERIMETER_MIN = 5.0
    private const val PERIMETER_MAX = 25.0

    /** Transit shape depends on order. MOVE is the exception to the arrival perimeter: it keeps its
     *  ordered infantry grid at the destination. DEFEND arrives into a PERIMETER ring sized to the
     *  squad; BARRAGE and RETREAT into SCATTER — see that shape's own doc note in [localOffset]. */
    private fun shapeFor(order: SquadOrder, arrived: Boolean): Shape {
        // A MOVE command is a formation movement command, including after the destination is
        // reached. Infantry presets map directly to 2x2, 2x4, and 4x4 grids.
        if (order == SquadOrder.MOVE) return Shape.GRID
        if (arrived) return when (order) {
            SquadOrder.DEFEND -> Shape.PERIMETER
            SquadOrder.BARRAGE, SquadOrder.RETREAT -> Shape.SCATTER
            else -> Shape.RING
        }
        return when (order) {
            SquadOrder.ATTACK -> Shape.WEDGE
            SquadOrder.DEFEND, SquadOrder.BARRAGE, SquadOrder.RETREAT -> Shape.LINE
            SquadOrder.PATROL -> Shape.COLUMN
            SquadOrder.MOVE -> Shape.GRID
        }
    }

    /** Local (unrotated, +Z = forward/toward anchor) offset for the [slotIndex]-th member out of
     *  [squadSize]. WEDGE, LINE, and COLUMN use a point/leader slot at the anchor. GRID, RING, and
     *  SCATTER give every member its own point. */
    private fun localOffset(shape: Shape, slotIndex: Int, squadSize: Int, spacing: Double = SLOT_SPACING): Vec3 {
        if (shape == Shape.RING || shape == Shape.PERIMETER) {
            val count = squadSize.coerceAtLeast(1)
            val angle = 2.0 * Math.PI * slotIndex / count
            val radius = if (shape == Shape.PERIMETER) perimeterRadius(squadSize) else RING_RADIUS
            return Vec3(kotlin.math.sin(angle) * radius, 0.0, kotlin.math.cos(angle) * radius)
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
                squadSize <= 16 -> 4 to 4
                // Thirty-two: twice as wide rather than twice as deep.
                else -> 8 to 4
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
            Shape.GRID, Shape.RING, Shape.PERIMETER, Shape.SCATTER -> Vec3.ZERO // unreachable, handled above
        }
    }

    /** World-space point [mob] should path toward instead of the bare [anchor] — offset by its
     *  formation slot, rotated toward the squad's shared heading (ignored for RING/PERIMETER/SCATTER,
     *  rotation-symmetric by construction). [fallbackFacing] is only used when the leader itself can't supply
     *  a heading (dead/unloaded, or IS the mob asking) — see [headingFor]. [arrived] switches to the
     *  order's arrival shape — PERIMETER, SCATTER or RING; MOVE keeps its grid — see [shapeFor]. Falls back to [anchor] itself if [mob]
     *  isn't actually in a squad (shouldn't happen for real callers, but cheap to guard). */
    fun slotTarget(
        mob: NpcEntity, anchor: Vec3, fallbackFacing: Vec3, arrived: Boolean, spacing: Double = SLOT_SPACING
    ): Vec3 {
        val squad = mob.currentSquad() ?: return anchor
        val index = mob.slotIndex(squad)
        if (index < 0) return anchor
        val shape = shapeFor(squad.order, arrived)
        val rearSupport = squad.order == SquadOrder.ATTACK && mob.npcClass.minimumCombatDistance > 0.0
        val local = if (rearSupport)
            attackOffset(mob.npcClass, index, squad.members.size, spacing)
        else localOffset(shape, index, squad.members.size, spacing)
        if (local == Vec3.ZERO) return anchor

        // Not rotated: RING/PERIMETER/SCATTER already assign each
        // slot its own angle across the FULL circle (see localOffset), so rotating that offset by
        // a heading vector adds nothing to the overall distribution — but for one SPECIFIC slot
        // tracked over time, it makes that slot's target point continuously rotate around the
        // anchor whenever the heading itself isn't perfectly still — and once arrived it never is:
        // it's anchor-minus-leader's-position, and the leader is itself spread round the point and
        // drifting. Every other member then chased a continuously rotating target — a defending
        // squad circling its own defend point with nobody to fight.
        if ((shape == Shape.RING && !rearSupport) ||
            shape == Shape.PERIMETER || shape == Shape.SCATTER) return anchor.add(local)

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

    /** Support stays behind the attack point even after the assault arrives or its lead man dies. */
    internal fun attackOffset(
        cls: NpcClass, slotIndex: Int, squadSize: Int, spacing: Double = SLOT_SPACING
    ): Vec3 {
        if (cls.minimumCombatDistance == 0.0) return localOffset(Shape.WEDGE, slotIndex, squadSize, spacing)
        // Twelve distinct rear posts cover the largest preset's twelve support members. Bound
        // the sideways spread so their posts stay within the 72-block engagement range.
        val post = slotIndex.mod(12)
        return Vec3(
            (post % 4 - 1.5) * spacing, 0.0,
            -cls.minimumCombatDistance - (post / 4) * SLOT_SPACING
        )
    }

    /** The [slotIndex]-th of [squadSize]'s place in [order]'s marching formation — wedge, line,
     *  column or grid — relative to the formation's point, +Z forward, not yet turned to a heading. */
    fun transitOffset(order: SquadOrder, slotIndex: Int, squadSize: Int): Vec3 =
        localOffset(shapeFor(order, false), slotIndex, squadSize)

    /** Where the [slotIndex]-th of [squadSize] stands in the MOVE grid relative to its centre, not
     *  turned to any heading — what a MOVE order with no distance to cover forms up into. */
    fun gridOffset(slotIndex: Int, squadSize: Int): Vec3 = localOffset(Shape.GRID, slotIndex, squadSize)

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
