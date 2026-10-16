package com.sbwnpc.squad.combat.tactics.operation

import com.sbwnpc.squad.combat.tactics.TacticalMember
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class AttackRole { CENTER, LEFT, RIGHT, RESERVE }
enum class AttackStage { DEPLOYING, ENGAGING, ADVANCING, COMPLETED }

/** World-free input. Contacts and covering fire are supplied by the existing perception system. */
data class AttackForce(
    val id: UUID,
    val stamp: Int,
    val faction: SquadFaction,
    val objective: Vec3,
    val center: Vec3,
    val members: List<TacticalMember>,
    val covering: Int = 0,
    val failedRoutes: Int = 0,
    val targetId: UUID? = null,
    val blockedShooters: Int = 0,
    val underFire: Boolean = false,
    val unlocatedShooters: Int = 0,
) {
    val fighters get() = members.filter { it.ready && it.health >= 0.3 && it.primaryAmmo && !it.sidearmOnly && it.role != NpcClass.MEDIC }
    val needsFiringApproach get() = blockedShooters + unlocatedShooters >= maxOf(2, (fighters.size + 1) / 2)
}

/** A versioned order to one squad. Weapon targets remain individual, observed enemies. */
data class AttackAssignment(
    val operation: Long,
    val role: AttackRole,
    val stage: AttackStage,
    val revision: Int,
    val waypoint: Vec3,
    val sector: Vec3,
    val facing: Vec3,
    val corridor: AttackCorridor,
    val deployed: Boolean,
    val blocked: Boolean,
    val externalCover: Boolean,
    val slots: Map<UUID, Double>,
    val blockedShooters: Int = 0,
    val covering: Int = 0,
    val underFire: Boolean = false,
    val unlocatedShooters: Int = 0,
) {
    val holding get() = blocked || role == AttackRole.RESERVE
}

/** A broad squad corridor, not an alternative Minecraft pathfinder. Height is checked natively. */
data class AttackCorridor(val points: List<Vec3>, val halfWidth: Double) {
    init {
        require(points.isNotEmpty() && halfWidth >= 0.0)
    }

    fun distance(point: Vec3): Double =
        if (points.size == 1) {
            point.subtract(points.first()).horizontalDistance()
        } else {
            points.zipWithNext().minOf { (a, b) ->
                val segment = b.subtract(a).multiply(1.0, 0.0, 1.0)
                val along = if (segment.lengthSqr() < 1.0e-6) 0.0 else point.subtract(a).dot(segment) / segment.lengthSqr()
                point.subtract(a.add(segment.scale(along.coerceIn(0.0, 1.0)))).horizontalDistance()
            }
        }

    /** Scattered survivors can join their strip, but cannot use the next squad's strip as a shortcut. */
    fun permits(from: Vec3, point: Vec3): Boolean = distance(point) <= halfWidth + 4.0 || distance(point) < distance(from) - 0.5

    /** Minecraft's first path node is the actor's current block center, not a proposed post. */
    fun permitsRoute(from: Vec3, point: Vec3): Boolean = point.subtract(from).horizontalDistance() <= 1.0 || permits(from, point)
}

data class AttackOperationEvent(val operation: Long, val tick: Long, val action: String, val squad: UUID?, val role: AttackRole?, val reason: String, val point: Vec3? = null)

fun interface AttackOperationEvents {
    fun emit(event: AttackOperationEvent)
    companion object {
        val NONE = AttackOperationEvents { }
    }
}
