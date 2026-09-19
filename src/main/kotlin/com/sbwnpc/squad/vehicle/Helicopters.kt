package com.sbwnpc.squad.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.BlockGetter
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * The SBW helicopters NPC crews can operate, and what their seats mean.
 *
 * Both are plain data-driven [VehicleEntity]s with no subclass of their own, so they can only be
 * told apart by entity type. The seat layouts differ in the way that matters most for an AI crew:
 *
 *  - Mi-28 has two seats and declares `TurretControllerIndex: 1`, so seat 1 is a real auto-aimable
 *    turret (30mm cannon plus missiles) — the same mechanism tank and IFV gunners already use.
 *  - AH-6 has no turret at all: its cannon and rockets are fixed to the airframe, which means
 *    aiming them is a flying problem, not a gunnery one. Its other three seats are bare benches.
 *    It is therefore a transport here, not a gunship.
 */
object Helicopters {
    val GUNSHIP: EntityType<*> get() = ModEntities.MI_28.get()
    val TRANSPORT: EntityType<*> get() = ModEntities.AH_6.get()

    /** Seat that owns the auto-aimable turret on the gunship; AH-6 has no equivalent. */
    const val GUNNER_SEAT = 1

    fun isHelicopter(vehicle: VehicleEntity): Boolean =
        vehicle.type == GUNSHIP || vehicle.type == TRANSPORT

    fun hasTurret(vehicle: VehicleEntity): Boolean = vehicle.type == GUNSHIP

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

    private fun clearColumn(level: BlockGetter, x: Int, y: Int, z: Int, needed: Int): Boolean {
        for (dy in 0 until needed) {
            if (!level.getBlockState(BlockPos(x, y + dy, z)).isAir) return false
        }
        return true
    }

    /** Ground level under a point, as the flight code measures it. */
    fun groundY(level: ServerLevel, x: Double, z: Double): Int =
        level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())

    private const val ROTOR_CLEARANCE = 6
    private const val MAX_LIFT = 24
}
