package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.HelicopterModel
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.Vec3

/**
 * The SBW helicopters NPC crews can operate, and what their seats mean.
 *
 * Both are plain data-driven SBW vehicles with no subclass of their own, so they can only be
 * told apart by entity type. The seat layouts differ in the way that matters most for an AI crew:
 *
 *  - Mi-28 has two seats and declares `TurretControllerIndex: 1`, so seat 1 is a real auto-aimable
 *    turret (30mm cannon plus missiles) — the same mechanism tank and IFV gunners already use.
 *  - AH-6 has no turret at all: its cannon and rockets are fixed to the airframe, which means
 *    aiming them is a flying problem, not a gunnery one. Its other three seats are bare benches.
 *    It is therefore a transport here, not a gunship.
 */
object Helicopters {
    /** Seat that owns the auto-aimable turret on the gunship; AH-6 has no equivalent. */
    const val GUNNER_SEAT = 1

    /**
     * How far the squad's objective has to be before a lift beats marching. Shared deliberately:
     * the riders' "worth boarding" and the transport pilot's "worth taking off" have to be the
     * same number, or there is a band of distances where the aircraft leaves for a trip its
     * passengers never considered getting on.
     */
    const val AIR_TRANSPORT_DISTANCE = 80.0

    fun isHelicopter(vehicle: Entity): Boolean = Ports.vehicles.modelOf(vehicle) is HelicopterModel

    /** Healthy and charged enough to be worth flying. Breaks off far above SBW's 10% health, where
     *  it takes the controls away for good. */
    fun canFly(vehicle: Entity): Boolean =
        Ports.vehicles.healthFraction(vehicle) > RETREAT_HEALTH_FRACTION &&
            Ports.vehicles.storedPower(vehicle) > MIN_RESERVE_ENERGY

    fun hasTurret(vehicle: Entity): Boolean = Ports.vehicles.modelOf(vehicle) == HelicopterModel.MI_28

    /**
     * Lowest Y at or above [startY] with [needed] blocks of clear air above it, searched up to
     * [MAX_LIFT]. A helicopter dropped under a canopy or inside a room starts its life chopping
     * into blocks, and unlike a ground vehicle it cannot simply sit there and wait — with no
     * first passenger SBW auto-levels it and bleeds the rotor off, so it just sinks.
     */
    fun clearSpawnY(level: BlockGetter, x: Double, z: Double, startY: Int, needed: Int = ROTOR_CLEARANCE): Double {
        val bx = Math.floor(x).toInt()
        val bz = Math.floor(z).toInt()
        var y = startY
        val ceiling = startY + MAX_LIFT
        while (y <= ceiling) {
            if (clearColumn(level, bx, y, bz, needed)) return y.toDouble()
            y++
        }
        return (startY + MAX_LIFT).toDouble()
    }

    /**
     * Whether a column is somewhere a helicopter can actually put down and let people out.
     *
     * The heightmap the flight code flies by counts leaves, so an aircraft "landing" on the
     * objective in a forest settles onto the canopy and the passengers step out into treetops.
     * This checks that the surface itself is solid and that there is open air above it.
     */
    fun isLandable(level: ServerLevel, x: Double, z: Double): Boolean {
        val bx = Math.floor(x).toInt()
        val bz = Math.floor(z).toInt()
        val surface = groundY(level, x, z)
        val ground = level.getBlockState(BlockPos(bx, surface - 1, bz))
        if (!ground.isSolidRender(level, BlockPos(bx, surface - 1, bz))) return false
        return clearColumn(level, bx, surface, bz, ROTOR_CLEARANCE)
    }

    /**
     * [preferred] if it will do, else the closest spot around it that will. Searched in rings so a
     * landing zone ends up as near the ordered point as the terrain allows.
     */
    fun findLandingSpot(level: ServerLevel, preferred: Vec3, radius: Int = LZ_SEARCH_RADIUS): Vec3 {
        if (isLandable(level, preferred.x, preferred.z)) return preferred
        var ring = LZ_STEP
        while (ring <= radius) {
            for (i in 0 until LZ_SAMPLES) {
                val angle = i * (Math.PI * 2 / LZ_SAMPLES)
                val x = preferred.x + Math.cos(angle) * ring
                val z = preferred.z + Math.sin(angle) * ring
                if (isLandable(level, x, z)) return Vec3(x, preferred.y, z)
            }
            ring += LZ_STEP
        }
        return preferred
    }

    private fun clearColumn(level: BlockGetter, x: Int, y: Int, z: Int, needed: Int): Boolean {
        for (dy in 0 until needed) {
            if (!level.getBlockState(BlockPos(x, y + dy, z)).isAir) return false
        }
        return true
    }

    /**
     * Whether an aircraft is down, for anything that has to know before it lets someone in or out.
     *
     * `onGround()` on its own is not enough — a parked AH-6 sits about a block above what the
     * heightmap calls the surface and can report false — so a low, settled aircraft counts as well.
     * Both halves of that matter: height alone would also be true of one descending through the
     * flare, which is emphatically not the moment to let passengers out, so it has to have stopped
     * moving vertically too.
     */
    fun isGrounded(level: ServerLevel, vehicle: Entity): Boolean {
        if (vehicle.onGround()) return true
        val height = vehicle.y - groundY(level, vehicle.x, vehicle.z)
        return height <= ON_DECK_HEIGHT && Math.abs(vehicle.deltaMovement.y) < SETTLED_VERTICAL_SPEED
    }

    /** Ground level under a point, as the flight code measures it. */
    fun groundY(level: ServerLevel, x: Double, z: Double): Int =
        level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())

    private const val ROTOR_CLEARANCE = 6
    private const val RETREAT_HEALTH_FRACTION = 0.35f
    private const val MIN_RESERVE_ENERGY = 200_000
    private const val MAX_LIFT = 24
    private const val LZ_SEARCH_RADIUS = 24
    private const val LZ_STEP = 6
    private const val LZ_SAMPLES = 8
    /** A parked AH-6 reads about a block up from the heightmap surface; a landing flare starts at
     *  three. This sits between them. */
    private const val ON_DECK_HEIGHT = 2.0
    private const val SETTLED_VERTICAL_SPEED = 0.05
}
