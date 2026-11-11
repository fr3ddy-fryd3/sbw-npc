package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Single shared raycast primitive for "is there solid terrain between these two points" — both
 * the cover search ([com.sbwnpc.squad.entity.ai.CoverSearch]: a candidate only counts as real cover if EVERY nearby threat is blocked
 * from it, and a peek point is only used if the target is NOT blocked from it) and
 * `GunAttackBehaviour`'s partial-cover firing-position logic (a candidate's concealment score is how
 * high up a raycast from the target gets blocked) need the exact same block-collider raycast.
 * Pulled out once both needed it, instead of duplicating the `ClipContext` call a second time.
 *
 * Every call is charged against [TickBudget] so the bulk searches can pace themselves.
 */
object Sightline {
    /** A hull has to start ahead of the muzzle by at least this to count as being in the way. */
    private const val AHEAD_EPSILON = 1.0e-4

    /** See [ownHullInLane]: how far past the target a missed round still finds a hull. */
    private const val BACKSTOP = 4.0

    /** See [ownHullInLane]: the cone allowance for the shooter's own side's hulls. */
    private const val MAX_OWN_CONE_ALLOWANCE = 3.0

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
    ): Boolean = crosses(hulls, from, to) || blocked(level, from, to, passer)

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
        // Nor a hull the target is standing in — just dismounted and still inside the box, say.
        // Every line to it enters that hull first, so counting it meant no firing position
        // anywhere would ever do. Not for a mob, which never rode it, nor with the shooter's own
        // side aboard: a zombie pressed against a squad's LAV touched its box too, and the squad
        // shot into the LAV (in-game 24.10).
        val targetBody = target?.boundingBox
        return Ports.vehicles.within(level, area) { vehicle ->
            Ports.vehicles.isOperational(vehicle) &&
                vehicle !== ridden && vehicle !== targetRide && vehicle !== target &&
                (targetBody == null || !vehicle.boundingBox.intersects(targetBody) || keepsHull(vehicle, shooter, target))
        }.map { it.boundingBox }
    }

    /**
     * Whether a shot from [from] at [aim] can put rounds into a vehicle of [shooter]'s side: in
     * front of the target, or within [BACKSTOP] behind it, where the misses go. [crosses] stops at
     * the target and has no cone, and in the dark a squad emptied its rifles past a zombie into its
     * own LAV (in-game 25.10). The spread is SBW's, as in [FiringCone].
     *
     * A vehicle is the shooter's when one of his side rides it, or, empty, when it stands within
     * [OWN_EMPTY_RADIUS] of him or one of his side is assigned to it. Every empty vehicle counted, and
     * an enemy's dismounted transport among its men made the ground ten blocks in front of it
     * unshootable from anywhere (in-game 03.11).
     */
    fun ownHullInLane(shooter: Entity, from: Vec3, aim: Vec3, spread: Double, target: Entity?): Boolean {
        val level = shooter.level() as? ServerLevel ?: return false
        val end = aim.add(aim.subtract(from).normalize().scale(BACKSTOP))
        val fan = Math.tan(FiringCone.angleOf(spread).coerceAtMost(Math.PI / 2 - AHEAD_EPSILON))
        val ridden = shooter.vehicle
        val targetRide = target?.vehicle
        return Ports.vehicles.within(level, AABB(from, end).inflate(MAX_OWN_CONE_ALLOWANCE)) { vehicle ->
            Ports.vehicles.isOperational(vehicle) && vehicle !== ridden && vehicle !== targetRide && vehicle !== target && ours(vehicle, shooter, from, level)
        }.any { vehicle ->
            val hull = vehicle.boundingBox
            entersAhead(hull.inflate(Math.min(from.distanceTo(hull.center) * fan, MAX_OWN_CONE_ALLOWANCE)), from, end)
        }
    }

    /** See [ownHullInLane]: whether [vehicle] is [shooter]'s side's. */
    // Near him it is his whoever it is assigned to, which also spares the scan of every NPC: this
    // runs for each candidate post. One assigned to both sides counts as ours, erring towards not firing.
    private fun ours(vehicle: Entity, shooter: Entity, from: Vec3, level: ServerLevel): Boolean {
        if (vehicle.passengers.isNotEmpty()) return vehicle.passengers.any { SquadTeams.sameSide(it, shooter) }
        if (vehicle.boundingBox.distanceToSqr(from) <= OWN_EMPTY_RADIUS * OWN_EMPTY_RADIUS) return true
        return NpcRegistry.all(level).any { it.isAlive && it.assignedVehicleId == vehicle.uuid && SquadTeams.sameSide(it, shooter) }
    }

    /** See [ownHullInLane]: how near the shooter an empty vehicle nobody is assigned to counts as his. */
    private const val OWN_EMPTY_RADIUS = 16.0

    private fun keepsHull(vehicle: Entity, shooter: Entity, target: Entity?) = (target != null && SquadTeams.sideOf(target) == null) || vehicle.passengers.any { SquadTeams.sameSide(it, shooter) }

    /**
     * Whether a shot from [from] at [to] runs into one of [hulls], along the line itself. A cone
     * around it, even capped at a block, refused every shot at a man standing a couple of blocks
     * beside a vehicle (in-game 03.11): the hull in the way is the one the line goes through.
     */
    fun crosses(hulls: List<AABB>, from: Vec3, to: Vec3): Boolean = hulls.any { entersAhead(it, from, to) }

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
