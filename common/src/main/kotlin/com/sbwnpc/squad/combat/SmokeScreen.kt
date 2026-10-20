package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * The smoke a squad throws between itself and the enemy as it starts to fall back.
 *
 * A quarter of the squad throws, rounded down, from the half that is holding while the other
 * bounds, so the runners are screened by men who stay to cover them. The grenades go down side by
 * side across the line to the enemy: thrown by the nearest men, all landed in one spot and made
 * one small cloud (#115, in-game 20.10). A man whose throw is not possible (a wall, a squadmate in
 * the way) leaves his point to the next one.
 */
object SmokeScreen {
    /** How far in front of the throwers the screen is laid: inside [GrenadeThrower]'s reach, and far enough that the cloud is not on them. */
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
     * Where [count] grenades go: on a line across the way to [enemy], [SCREEN_DISTANCE] in front of
     * [centre] on its level, [SCREEN_SPACING] apart and centred on that way. Empty when the enemy
     * gives no direction.
     */
    internal fun screenPoints(centre: Vec3, enemy: Vec3, count: Int): List<Vec3> {
        val flat = Vec3(enemy.x - centre.x, 0.0, enemy.z - centre.z)
        if (flat.lengthSqr() < 1.0 || count <= 0) return emptyList()
        val ahead = flat.normalize()
        val across = Vec3(-ahead.z, 0.0, ahead.x)
        val middle = centre.add(ahead.scale(SCREEN_DISTANCE))
        return (0 until count).map { middle.add(across.scale((it - (count - 1) / 2.0) * SCREEN_SPACING)) }
    }

    /**
     * Throws up to [count] grenades of the screen from [holding] towards [enemy], each point by the
     * nearest man who can make it; how many went.
     */
    fun throwFrom(level: ServerLevel, holding: List<NpcEntity>, enemy: Vec3, count: Int): Int {
        val free = throwers(holding).toMutableList()
        if (free.isEmpty()) return 0
        val centre = Vec3(free.sumOf { it.x } / free.size, free.sumOf { it.y } / free.size, free.sumOf { it.z } / free.size)
        var thrown = 0
        for (point in screenPoints(centre, enemy, count)) {
            // A smoke grenade flies like the hand grenades, so their arc check judges it.
            val thrower = free.sortedBy { it.position().distanceToSqr(point) }.firstOrNull {
                GrenadeThrower.inRange(it.position(), point) && Ports.grenades.arcClear(it, level, point, Vec3.ZERO, GrenadeKind.DEFENSIVE, NEAR_POINT)
            } ?: continue
            Ports.grenades.throwSmoke(thrower, level, point)
            thrower.smokeLeft--
            free -= thrower
            thrown++
            DebugFlags.log(LogGroup.RETREAT, "{} threw smoke at {} towards {} from {}", thrower.uuid, point, enemy, thrower.position())
        }
        return thrown
    }
}
