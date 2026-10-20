package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * The smoke grenade a squad throws between itself and the enemy as it starts to fall back.
 *
 * One man throws it, from the half that is holding while the other bounds, so the runners are
 * screened by someone who stays to cover them. The man nearest the enemy goes first; if his throw
 * is not possible (a wall, a squadmate in the way) the next one tries.
 */
object SmokeScreen {
    /** How far in front of the thrower the grenade is aimed: inside [GrenadeThrower]'s reach, and far enough that the cloud is not on him. */
    private const val SCREEN_DISTANCE = 10.0

    /** An arc that comes down this close to the point is a good throw. */
    private const val NEAR_POINT = 2.5

    /** Men tried before giving up on a withdrawal's screen. */
    private const val MAX_TRIES = 3

    /** The men who could throw, nearest to [enemy] first. */
    internal fun throwers(holding: List<NpcEntity>, enemy: Vec3): List<NpcEntity> =
        holding.filter { it.isAlive && it.smokeLeft > 0 && it.vehicle == null && !it.busyWithRole() }
            .sortedBy { it.position().distanceToSqr(enemy) }

    /** Where a man at [from] aims the screen: [SCREEN_DISTANCE] towards [enemy] on the level. */
    internal fun screenPoint(from: Vec3, enemy: Vec3): Vec3? {
        val flat = Vec3(enemy.x - from.x, 0.0, enemy.z - from.z)
        if (flat.lengthSqr() < 1.0) return null
        return from.add(flat.normalize().scale(SCREEN_DISTANCE))
    }

    /** Throws the screen from one of [holding] towards [enemy]; whether any of them did. */
    fun throwFrom(level: ServerLevel, holding: List<NpcEntity>, enemy: Vec3): Boolean {
        for (thrower in throwers(holding, enemy).take(MAX_TRIES)) {
            val point = screenPoint(thrower.position(), enemy) ?: continue
            // A smoke grenade flies like the hand grenades, so their arc check judges it.
            if (!GrenadeThrower.inRange(thrower.position(), point) ||
                !Ports.grenades.arcClear(thrower, level, point, Vec3.ZERO, GrenadeKind.DEFENSIVE, NEAR_POINT)
            ) {
                continue
            }
            Ports.grenades.throwSmoke(thrower, level, point)
            thrower.smokeLeft--
            DebugFlags.log(LogGroup.RETREAT, "{} threw smoke towards {} from {}", thrower.uuid, enemy, thrower.position())
            return true
        }
        return false
    }
}
