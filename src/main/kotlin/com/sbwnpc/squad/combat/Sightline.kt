package com.sbwnpc.squad.combat

import net.minecraft.server.level.ServerLevel
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Single shared raycast primitive for "is there solid terrain between these two points" — both
 * `SeekCoverBehaviour` (a candidate only counts as real cover if EVERY nearby threat is blocked
 * from it, and a peek point is only used if the target is NOT blocked from it) and
 * `GunAttackBehaviour`'s partial-cover firing-position logic (a candidate's concealment score is how
 * high up a raycast from the target gets blocked) need the exact same block-collider raycast.
 * Pulled out once both needed it, instead of duplicating the `ClipContext` call a second time.
 *
 * Every call is charged against [TickBudget] so the bulk searches can pace themselves.
 */
object Sightline {
    /** See [crosses]: how far past a hull's own faces a shot still counts as hitting it. */
    private const val MAX_CONE_ALLOWANCE = 1.0
    /** A hull has to start ahead of the muzzle by at least this to count as being in the way. */
    private const val AHEAD_EPSILON = 1.0e-4

    fun blocked(level: ServerLevel, from: Vec3, to: Vec3, passer: Entity): Boolean {
        TickBudget.chargeRaycast(level)
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, passer))
        return hit.type == HitResult.Type.BLOCK
    }

    /**
     * Blocked by terrain or by a vehicle hull.
     *
     * The cheap test goes first on purpose: [crosses] is plain box maths, so a line already cut by
     * an APC never pays for a raycast at all.
     */
    fun blockedBy(
        level: ServerLevel,
        from: Vec3,
        to: Vec3,
        passer: Entity,
        hulls: List<AABB>,
        spreadDegrees: Double = 0.0
    ): Boolean = crosses(hulls, from, to, spreadDegrees) || blocked(level, from, to, passer)

    /**
     * Hulls of vehicles a shot has no business going through, for the area a shooter is working in.
     *
     * [blocked] and vanilla's own `hasLineOfSight` are block raycasts: an entity standing between
     * shooter and target is simply not there as far as they are concerned. A parked APC is a wall
     * in every practical sense, so without this an NPC will happily take up a firing position
     * behind one and empty its magazine into the armour.
     *
     * Two vehicles are deliberately not obstacles: the one the shooter is riding, and the one the
     * target is riding — SBW aims at the vehicle when its occupant is the target, so hitting that
     * hull is the point.
     *
     * Returned as plain boxes and queried once for a whole search, because the useful callers test
     * many candidate lines in a row and an entity query per line would be far too expensive.
     */
    fun vehicleHulls(level: ServerLevel, area: AABB, shooter: Entity, target: Entity?): List<AABB> {
        val ridden = shooter.vehicle
        val targetRide = target?.vehicle
        return level.getEntitiesOfClass(VehicleEntity::class.java, area) { vehicle ->
            vehicle.isAlive && !vehicle.isWreck &&
                vehicle !== ridden && vehicle !== targetRide && vehicle !== target
        }.map { it.boundingBox }
    }

    /**
     * Whether a shot from [from] at [to] runs into one of [hulls].
     *
     * [spreadDegrees] widens each hull a little, since rounds leave along a cone rather than the
     * centre line. It is capped at [MAX_CONE_ALLOWANCE] on purpose: the cone really does fan out
     * by metres over a long shot, but refusing to fire because one stray round might clip a hull
     * forty blocks away turns every vehicle into a no-shooting bubble several blocks wide.
     */
    fun crosses(hulls: List<AABB>, from: Vec3, to: Vec3, spreadDegrees: Double = 0.0): Boolean {
        if (hulls.isEmpty()) return false
        val fan = if (spreadDegrees <= 0.0) 0.0 else Math.tan(Math.toRadians(spreadDegrees))
        return hulls.any { hull ->
            val reach = if (fan <= 0.0) 0.0
                else Math.min(from.distanceTo(hull.center) * fan, MAX_CONE_ALLOWANCE)
            entersAhead(hull.inflate(reach), from, to)
        }
    }

    /**
     * Whether the segment runs into [box] somewhere ahead of [from] and before it reaches [to].
     *
     * The "ahead" part carries the weight. A vehicle's box is drawn around a shape that is not a
     * box, so a soldier standing against the side of one is routinely inside it — and answering
     * "blocked" for that stops the soldier shooting in any direction at all, including straight
     * away from the vehicle. Starting inside therefore means the hull is around us rather than in
     * front of us, and the shot leaves unhindered; only a hull the line actually runs into counts.
     */
    private fun entersAhead(box: AABB, from: Vec3, to: Vec3): Boolean {
        val d = to.subtract(from)
        var enter = Double.NEGATIVE_INFINITY
        var exit = Double.POSITIVE_INFINITY

        fun slab(origin: Double, delta: Double, min: Double, max: Double): Boolean {
            if (Math.abs(delta) < 1.0e-7) return origin in min..max
            val t1 = (min - origin) / delta
            val t2 = (max - origin) / delta
            enter = Math.max(enter, Math.min(t1, t2))
            exit = Math.min(exit, Math.max(t1, t2))
            return true
        }

        if (!slab(from.x, d.x, box.minX, box.maxX)) return false
        if (!slab(from.y, d.y, box.minY, box.maxY)) return false
        if (!slab(from.z, d.z, box.minZ, box.maxZ)) return false
        if (enter > exit) return false
        return enter > AHEAD_EPSILON && enter <= 1.0
    }
}
