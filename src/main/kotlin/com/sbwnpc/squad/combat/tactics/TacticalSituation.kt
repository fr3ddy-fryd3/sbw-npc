package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3

/** Derived facts for one assessment. Distances are blocks; contact velocity is blocks per tick. */
internal class TacticalSituation(val view: TacticalSnapshot) {
    val visible = view.visible
    val fighters = view.fighting
    val focus = visible.maxByOrNull { focusPriority(it) }?.position
        ?: view.contacts.maxByOrNull { it.seenAt }?.position

    val hasHeightAdvantage get() = focus?.let { view.center.y - it.y >= HEIGHT_DIFFERENCE } == true
    val enemyHasHeightAdvantage get() = focus?.let { it.y - view.center.y >= HEIGHT_DIFFERENCE } == true
    val hasRockets get() = fighters.any { it.rockets }
    val hasTooFewAttackers get() = fighters.count { it.role != NpcClass.MEDIC } < 2
    val lostHalfStrength get() = fighters.size < maxOf(1, view.peakStrength / 2)
    val majoritySuppressed get() = view.members.count { it.suppressed } * 2 >= view.members.size
    val firstArmouredContact get() = visible.firstOrNull { it.armoured || it.airborne }

    fun attackedFromMultipleFronts(): Boolean {
        val origin = view.threatOrigin ?: view.center
        val directions = (visible.map { it.position } + view.incoming)
            .map { horizontalDirection(origin, it) }
            .filter { it.lengthSqr() > MIN_DIRECTION_LENGTH_SQUARED }
        val attackDirection = horizontalDirection(origin, view.home ?: focus ?: origin)
        val allThreatsAhead = view.offensive && attackDirection.lengthSqr() > MIN_DIRECTION_LENGTH_SQUARED &&
            directions.all { it.dot(attackDirection) >= FORWARD_SECTOR_DOT }
        if (allThreatsAhead) return false

        return directions.indices.any { first ->
            directions.indices.any { second ->
                first < second && directions[first].dot(directions[second]) < SEPARATE_FRONTS_DOT
            }
        }
    }

    fun hasCloseOrApproachingEnemy(): Boolean =
        visible.any { contact ->
            val distance = contact.position.distanceTo(view.center)
            val towardsSquad = view.center.subtract(contact.position).normalize()
            distance < CLOSE_ENEMY_DISTANCE ||
                (distance < APPROACHING_ENEMY_DISTANCE && contact.velocity.dot(towardsSquad) > MOVING_SPEED)
        }

    fun enemiesWithdrawing(): Boolean =
        visible.all { contact ->
            val awayFromSquad = contact.position.subtract(view.center).normalize()
            contact.velocity.dot(awayFromSquad) > MOVING_SPEED
        }

    fun enemyFrontIsSpread(): Boolean = visible.any { it.position.distanceTo(requireNotNull(focus)) > SPREAD_FRONT_DISTANCE }

    fun enemyIsCompact(): Boolean = visible.all { it.position.distanceTo(requireNotNull(focus)) <= COMPACT_ENEMY_RADIUS }

    fun isolatedSector(): Vec3 =
        visible.minBy { candidate ->
            val nearbyEnemies = visible.count { it.position.distanceTo(candidate.position) < COMPACT_ENEMY_RADIUS }
            nearbyEnemies + candidate.position.distanceTo(view.center) * SECTOR_DISTANCE_WEIGHT -
                candidate.priority * SECTOR_PRIORITY_WEIGHT
        }.position

    private fun focusPriority(contact: TacticalContact): Double = contact.priority / (1.0 + contact.position.distanceTo(view.center) / FOCUS_DISTANCE_SCALE)

    private fun horizontalDirection(from: Vec3, to: Vec3): Vec3 = to.subtract(from).multiply(1.0, 0.0, 1.0).normalize()

    private companion object {
        const val HEIGHT_DIFFERENCE = 8.0
        const val CLOSE_ENEMY_DISTANCE = 8.0
        const val APPROACHING_ENEMY_DISTANCE = 22.0
        const val MOVING_SPEED = 0.08
        const val SPREAD_FRONT_DISTANCE = 24.0
        const val COMPACT_ENEMY_RADIUS = 14.0
        const val FOCUS_DISTANCE_SCALE = 40.0
        const val SECTOR_DISTANCE_WEIGHT = 0.01
        const val SECTOR_PRIORITY_WEIGHT = 0.15
        const val MIN_DIRECTION_LENGTH_SQUARED = 0.5
        const val FORWARD_SECTOR_DOT = -0.1
        const val SEPARATE_FRONTS_DOT = -0.2
    }
}
