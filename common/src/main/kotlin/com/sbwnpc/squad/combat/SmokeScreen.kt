package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

/**
 * The smoke a squad throws between itself and the enemy as it starts to fall back.
 *
 * A quarter of the squad throws, rounded down, from the half that is holding while the other
 * bounds, so the runners are screened by men who stay to cover them. The grenades go down side by
 * side across the line to the enemy, in front of the holding man nearest it, so they make one wide
 * screen and not one small cloud (#115). A man whose throw is not possible (a wall, a squadmate in
 * the way) leaves his point to the next one.
 */
object SmokeScreen {
    /**
     * How far in front of the throwers the screen is laid: inside [GrenadeThrower]'s reach, and far
     * enough that the cloud is not on them. A point no arc reaches is tried at half this, at the edge of
     * the thrower's own smoke, which still screens the men behind him.
     */
    private const val SCREEN_DISTANCE = 10.0

    /** Gap between neighbouring grenades across the screen, about one cloud's width. */
    private const val SCREEN_SPACING = 7.0

    /** One grenade for this many men in the squad. */
    private const val MEN_PER_GRENADE = 4

    /** An arc that comes down this close to the point is a good throw. */
    private const val NEAR_POINT = 2.5

    /** How many grenades a squad of [members] men throws for one withdrawal. */
    internal fun grenadesFor(members: Int): Int = members / MEN_PER_GRENADE

    /** The men who could throw. */
    internal fun throwers(holding: List<NpcEntity>): List<NpcEntity> = holding.filter { it.isAlive && it.smokeLeft > 0 && it.vehicle == null && !it.busyWithRole() }

    /**
     * Where [count] grenades go: on a line across the way to [enemy], [distance] in front of
     * [centre] on its level, [SCREEN_SPACING] apart and centred on that way. Empty when the enemy
     * gives no direction.
     */
    internal fun screenPoints(centre: Vec3, enemy: Vec3, count: Int, distance: Double = SCREEN_DISTANCE): List<Vec3> {
        val flat = Vec3(enemy.x - centre.x, 0.0, enemy.z - centre.z)
        if (flat.lengthSqr() < 1.0 || count <= 0) return emptyList()
        val ahead = flat.normalize()
        val across = Vec3(-ahead.z, 0.0, ahead.x)
        val middle = centre.add(ahead.scale(distance))
        return (0 until count).map { middle.add(across.scale((it - (count - 1) / 2.0) * SCREEN_SPACING)) }
    }

    /**
     * [point] moved onto the ground of its column: laid at the throwers' height, on a hillside the
     * point was inside the slope or over a drop, and no arc reached it. Under a roof this is the
     * roof, which no arc reaches either; that throw is left out and traced.
     */
    private fun onGround(level: ServerLevel, point: Vec3): Vec3 {
        val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(point.x), Mth.floor(point.z))
        return Vec3(point.x, surface.toDouble(), point.z)
    }

    /**
     * Throws up to [count] grenades of the screen from [holding] towards [enemy], each point by the
     * nearest man who can make it; how many went.
     */
    fun throwFrom(level: ServerLevel, holding: List<NpcEntity>, enemy: Vec3, count: Int): Int {
        val free = throwers(holding).toMutableList()
        if (free.isEmpty()) return 0
        // In front of the man nearest the enemy: from the middle of a squad strung out over fifty
        // blocks the screen fell where nobody could throw that far.
        val centre = free.minBy { it.position().distanceToSqr(enemy) }.position()
        var thrown = 0
        // Each grenade's own place on the screen, and the same place nearer in: on a hillside the
        // far one was often below a drop that no arc from up the slope came down on.
        val far = screenPoints(centre, enemy, count).map { onGround(level, it) }
        val near = screenPoints(centre, enemy, count, SCREEN_DISTANCE / 2).map { onGround(level, it) }
        for ((farPoint, nearPoint) in far.zip(near)) {
            val choice = listOf(farPoint, nearPoint).firstNotNullOfOrNull { point -> throwerFor(level, free, point)?.let { point to it } }
            if (choice == null) {
                DebugFlags.log(LogGroup.RETREAT, "no throw for smoke at {}: {} men free, nearest {} blocks off", farPoint, free.size, free.minOfOrNull { it.position().distanceTo(farPoint) }?.toInt())
                continue
            }
            val (point, thrower) = choice
            Ports.grenades.throwSmoke(thrower, level, point)
            thrower.smokeLeft--
            free -= thrower
            thrown++
            DebugFlags.log(LogGroup.RETREAT, "{} threw smoke at {} towards {} from {}", thrower.uuid, point, enemy, thrower.position())
        }
        return thrown
    }

    /** The nearest of [free] who can land a grenade on [point]; a smoke grenade flies like the hand grenades, so their arc check judges it. */
    private fun throwerFor(level: ServerLevel, free: List<NpcEntity>, point: Vec3): NpcEntity? =
        free.sortedBy { it.position().distanceToSqr(point) }.firstOrNull {
            GrenadeThrower.inRange(it.position(), point) && Ports.grenades.arcClear(it, level, point, Vec3.ZERO, GrenadeKind.DEFENSIVE, NEAR_POINT)
        }
}
