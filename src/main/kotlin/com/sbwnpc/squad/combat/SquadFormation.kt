package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3

/**
 * Turns "every squad member walks toward the exact same point" (the literal `home`/route point
 * passed to `navigation.moveTo` in `SquadOrderGoal`) into "every squad member walks toward its own
 * slot around that point" — real formation-slot steering (see gdx-ai's Formation Motion, and RTS
 * flocking literature), not a full boids simulation. That single change is what was actually
 * causing both the "everyone piles onto one spot and shoves past each other" pileup and the
 * resulting "snake" of NPCs re-routing around each other every tick.
 *
 * Shape is picked from the squad's current order rather than exposed as a player choice — matches
 * real infantry doctrine roughly (wedge to advance into unclear contact / ATTACK, line abreast to
 * hold a wide front / DEFEND, staggered column to travel a route / PATROL) without needing new UI.
 *
 * Deliberately simple: each mob computes its own "facing" as the vector from its own current
 * position to the anchor, rather than sharing one heading across the whole squad (which would need
 * a squad-wide centroid/leader reference the code doesn't track). Squad members are normally
 * already clustered near each other when this runs, so their individual headings end up close
 * enough in practice for a recognizable wedge/line/column — this is not a perfectly rigid formation
 * and isn't trying to be one.
 */
object SquadFormation {

    private const val SLOT_SPACING = 2.5
    private const val RING_RADIUS = 3.5

    private enum class Shape { WEDGE, LINE, COLUMN, RING }

    /** Transit shape depends on order (advance/hold/travel); once the squad has actually reached
     *  where it's going, [arrived] forces a perimeter instead — a squad standing still in a wedge
     *  or line looks wrong (per user feedback), real held positions look like a ring with everyone
     *  facing outward, not a marching formation frozen in place. */
    private fun shapeFor(order: SquadOrder, arrived: Boolean): Shape {
        if (arrived) return Shape.RING
        return when (order) {
            SquadOrder.ATTACK -> Shape.WEDGE
            SquadOrder.DEFEND -> Shape.LINE
            SquadOrder.PATROL -> Shape.COLUMN
            SquadOrder.FREE -> Shape.COLUMN
        }
    }

    /** Local (unrotated, +Z = forward/toward anchor) offset for the [slotIndex]-th member out of
     *  [squadSize]. For the transit shapes, index 0 is the point/leader slot and sits right on the
     *  anchor, the rest alternate left/right at increasing rank. RING ignores the leader distinction
     *  entirely — every member (including 0) gets an even slice of the perimeter. */
    private fun localOffset(shape: Shape, slotIndex: Int, squadSize: Int): Vec3 {
        if (shape == Shape.RING) {
            val count = squadSize.coerceAtLeast(1)
            val angle = 2.0 * Math.PI * slotIndex / count
            return Vec3(kotlin.math.sin(angle) * RING_RADIUS, 0.0, kotlin.math.cos(angle) * RING_RADIUS)
        }
        if (slotIndex <= 0) return Vec3.ZERO
        val rank = (slotIndex + 1) / 2
        val side = if (slotIndex % 2 == 1) -1.0 else 1.0
        return when (shape) {
            // Spreads out sideways AND drops back per rank — the classic V/wedge shape.
            Shape.WEDGE -> Vec3(side * rank * SLOT_SPACING, 0.0, -rank * SLOT_SPACING)
            // Spreads sideways only, same depth as the leader — a wide front.
            Shape.LINE -> Vec3(side * rank * SLOT_SPACING, 0.0, 0.0)
            // Mostly single-file, alternating slightly left/right (staggered column) rather than
            // dead in the last member's footsteps.
            Shape.COLUMN -> Vec3(side * SLOT_SPACING * 0.4, 0.0, -rank * SLOT_SPACING)
            Shape.RING -> Vec3.ZERO // unreachable, handled above
        }
    }

    /** World-space point [mob] should path toward instead of the bare [anchor] — offset by its
     *  formation slot, rotated toward [facing] (ignored for RING, which is rotation-symmetric
     *  anyway). [arrived] switches the shape to RING regardless of order — see [shapeFor]. Falls
     *  back to [anchor] itself if [mob] isn't actually in a squad (shouldn't happen for real
     *  callers, but cheap to guard) or [facing] is degenerate (mob standing exactly on the anchor
     *  already, non-RING shapes only). */
    fun slotTarget(mob: NpcEntity, anchor: Vec3, facing: Vec3, arrived: Boolean): Vec3 {
        val squad = mob.currentSquad() ?: return anchor
        val index = squad.members.indexOf(mob.uuid)
        if (index < 0) return anchor
        val local = localOffset(shapeFor(squad.order, arrived), index, squad.members.size)
        if (local == Vec3.ZERO) return anchor

        val flat = Vec3(facing.x, 0.0, facing.z)
        if (flat.lengthSqr() < 1.0e-6) return anchor.add(local)
        val fwd = flat.normalize()
        val right = Vec3(-fwd.z, 0.0, fwd.x)
        return anchor.add(fwd.scale(local.z)).add(right.scale(local.x))
    }
}
