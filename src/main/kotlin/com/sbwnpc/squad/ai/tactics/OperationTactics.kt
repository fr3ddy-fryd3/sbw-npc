package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Who assaults into the objective and where, and who goes after the enemies left around it. */
data class AttackRoles(val assault: Map<UUID, Point3> = emptyMap(), val clearing: Map<UUID, Point3> = emptyMap()) {
    companion object {
        val NONE = AttackRoles()
    }
}

/** A member as the operation's attack roles see it. */
data class AttackMember(val id: UUID, val position: Point3, val ready: Boolean)

/**
 * The operation's ways of taking its objective, as geometry and intent rewrites: no world access,
 * no executor state. [AttackPhase] decides when each applies; the HTN operation methods apply it.
 */
object OperationTactics {
    /** Most attackers sent after enemies left around a taken objective. */
    const val MAX_CLEARING_TEAM = 4

    /**
     * Roles for [phase]: the front movers assault into a loose ring inside [objective]; once it is
     * taken, the ready members nearest the [threats] (2 to [MAX_CLEARING_TEAM], keeping those
     * already sent) each go after the nearest one while the rest hold.
     */
    fun roles(phase: AttackPhase, objective: Point3, movers: List<UUID>, members: List<AttackMember>, threats: List<Point3>, previousClearing: Set<UUID>): AttackRoles {
        if (phase == AttackPhase.DEPLOY) return AttackRoles.NONE
        val clearing = if (phase == AttackPhase.CLEAR && threats.isNotEmpty()) {
            val ready = members.filter { it.ready }
            val size = (ready.size / 4).coerceIn(2, MAX_CLEARING_TEAM).coerceAtMost(ready.size)
            ready.sortedWith(compareBy<AttackMember> { it.id !in previousClearing }.thenBy { member -> threats.minOf { distance(it, member.position) } })
                .take(size)
                .associate { member -> member.id to threats.minBy { distance(it, member.position) } }
        } else {
            emptyMap()
        }
        val assault = movers.filter { it !in clearing }.sortedBy { it.toString() }.withIndex().associate { (index, id) ->
            // A loose ring, so the assault group does not pile onto one block.
            val ring = 4.0 + index / 8 * 4.0
            val angle = index * Math.PI / 4 + index / 8 * Math.PI / 8
            id to Point3(objective.x + cos(angle) * ring, objective.y, objective.z + sin(angle) * ring)
        }
        return AttackRoles(assault, clearing)
    }

    /**
     * [mission] with its members' intents rewritten for [roles]. An assault crosses open ground
     * under fire on purpose, so cover is preferred by exposure but not required, and the role
     * clearance from contacts is lifted for these members only.
     */
    fun apply(mission: SquadMission, roles: AttackRoles): SquadMission {
        if (roles == AttackRoles.NONE) return mission
        return mission.copy(
            teams = mission.teams.map { team ->
                team.copy(
                    members = team.members.map { intent ->
                        val target = roles.clearing[intent.actor.id]
                        val slot = roles.assault[intent.actor.id]
                        when {
                            // Special duties (inspection, rear guard, marching orders) keep their own work.
                            intent.duty !in setOf(CombatDuty.MOVE, CombatDuty.FIRE) -> intent

                            target != null -> intent.copy(duty = CombatDuty.SEARCH, anchor = target, focus = target, inspection = target, requireCover = false, requireShelter = false, minimumHeight = null, assault = true)

                            slot != null -> intent.copy(duty = CombatDuty.MOVE, anchor = slot, requireCover = false, requireShelter = false, minimumHeight = null, assault = true)

                            else -> intent
                        }
                    },
                )
            },
        )
    }

    private fun distance(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)
}
