package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.coordination.TacticalOrder
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Who assaults into the objective and where, who goes after the enemies left around it, and who
 * closes on the point's defenders to fire from nearer.
 */
data class AttackRoles(val assault: Map<UUID, Point3> = emptyMap(), val clearing: Map<UUID, Point3> = emptyMap(), val closing: Map<UUID, Point3> = emptyMap()) {
    companion object {
        val NONE = AttackRoles()
    }
}

/** A member as the operation's attack roles see it. */
data class AttackMember(val id: UUID, val position: Point3, val ready: Boolean)

/**
 * One member's situation for [OperationTactics.duty].
 *
 * @property elsewhere the order's objective is in another dimension.
 * @property inContact the operation has a live contact to fight.
 * @property observer a machine gunner or sniper with primary ammo, who holds a height in defence, in a fight too.
 * @property reachedMove the member's current MOVE post is reached.
 * @property wasFiring the member's previous intent was FIRE.
 */
data class DutyInputs(
    val order: TacticalOrder,
    val position: Point3,
    val proposal: FrontProposal,
    val objective: Point3?,
    val inspect: Point3?,
    val inContact: Boolean,
    val elsewhere: Boolean,
    val scout: Boolean,
    val observer: Boolean,
    val hasPatrolRoute: Boolean,
    val reachedMove: Boolean,
    val wasFiring: Boolean,
)

data class DutyChoice(val duty: CombatDuty, val anchor: Point3)

/**
 * Each member's duty in the operation and the ways of taking its objective, as geometry and intent
 * rewrites: no world access, no executor state. [AttackPhase] decides when each applies; the HTN operation methods apply it.
 */
object OperationTactics {
    /** Most attackers sent after enemies left around a taken objective. */
    const val MAX_CLEARING_TEAM = 4

    /** Closing in, a mover halves its distance to the point's nearest defender, down to this. */
    const val CLOSE_DISTANCE = 20.0

    /**
     * Roles for [phase]. While defenders still hold the [objective] an assault closes in under
     * fire: each mover takes a firing post half as far from the nearest defender as it stood
     * when the assault began ([origins], see [closingPost]). Once none are left at the point the
     * movers occupy a loose ring inside it; once it is taken, the ready members nearest the
     * [threats] (2 to [MAX_CLEARING_TEAM], keeping those already sent) each go after the nearest
     * one while the rest hold.
     */
    fun roles(phase: AttackPhase, objective: Point3, movers: List<UUID>, members: List<AttackMember>, threats: List<Point3>, previousClearing: Set<UUID>, origins: Map<UUID, Point3> = emptyMap()): AttackRoles {
        if (phase == AttackPhase.DEPLOY) return AttackRoles.NONE
        // An assault is not a run onto the defenders: it shortens the range and keeps firing.
        val defenders = threats.filter { atObjective(it, objective) }
        if (phase == AttackPhase.ASSAULT && defenders.isNotEmpty()) {
            val positions = members.associate { it.id to it.position }
            val closing = movers.mapNotNull { id -> (origins[id] ?: positions[id])?.let { id to closingPost(it, defenders) } }.toMap()
            return AttackRoles(closing = closing)
        }
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
                        val post = roles.closing[intent.actor.id]
                        when {
                            // Special duties (inspection, observation, marching orders) keep their own
                            // work. The rear guard joins an assault: left behind 84 blocks back, the
                            // men a reinforcement added never reached the fight (#54).
                            intent.duty !in setOf(CombatDuty.MOVE, CombatDuty.FIRE, CombatDuty.REAR_GUARD) -> intent

                            target != null -> intent.copy(duty = CombatDuty.SEARCH, anchor = target, focus = target, inspection = target, requireCover = false, requireShelter = false, minimumHeight = null, assault = true)

                            slot != null -> intent.copy(duty = CombatDuty.MOVE, anchor = slot, requireCover = false, requireShelter = false, minimumHeight = null, assault = true)

                            // Closing in is still fire from cover, but inside the role's clearance:
                            // kept to 48 blocks, riflemen never reached a post 20-30 blocks from the
                            // defenders and the assault stood where it began (#54).
                            post != null -> intent.copy(duty = CombatDuty.FIRE, anchor = post, minimumHeight = null, assault = true)

                            else -> intent
                        }
                    },
                )
            },
        )
    }

    /**
     * What one member does under [order] and where: the front's [DutyInputs.proposal] applies only
     * in a fight on ATTACK; otherwise the member follows the order, observes or fires from where it
     * is. A fire post farther than [fireReach] is first approached as movement to [approachOffset]
     * short of it, because a local fire-post query cannot see a post beyond its reach.
     */
    fun duty(inputs: DutyInputs, fireReach: Double, approachOffset: Double): DutyChoice =
        with(inputs) {
            val proposal = proposal
            val approach = proposal.duty == CombatDuty.FIRE && distance3(position, proposal.anchor) > fireReach
            val duty = when {
                elsewhere -> CombatDuty.FOLLOW_ORDER

                scout && order == TacticalOrder.ATTACK -> CombatDuty.SEARCH

                // A sentry fights from his height. Sent to fire from where he stood, a gunner stayed below
                // the tower, and a sentry left it whenever a reported target hid (#73).
                (order == TacticalOrder.DEFEND || (order == TacticalOrder.PATROL && !hasPatrolRoute)) && observer -> CombatDuty.OBSERVE

                !inContact || order in setOf(TacticalOrder.RETREAT, TacticalOrder.MOVE, TacticalOrder.BARRAGE) -> CombatDuty.FOLLOW_ORDER

                order != TacticalOrder.ATTACK -> CombatDuty.FIRE

                // A mover that reached its post, or already fired, keeps firing instead of moving on.
                proposal.duty == CombatDuty.MOVE -> if (reachedMove || wasFiring) CombatDuty.FIRE else CombatDuty.MOVE

                approach -> CombatDuty.MOVE

                else -> proposal.duty
            }
            val anchor = when {
                duty == CombatDuty.SEARCH -> requireNotNull(inspect) { "a scout needs a point to inspect" }
                duty in setOf(CombatDuty.FOLLOW_ORDER, CombatDuty.OBSERVE) -> objective ?: position
                order != TacticalOrder.ATTACK -> position
                duty == CombatDuty.FIRE && proposal.duty == CombatDuty.MOVE -> position
                approach -> short(proposal.anchor, position, approachOffset)
                else -> proposal.anchor
            }
            DutyChoice(duty, anchor)
        }

    /** Half as far from the nearest of [defenders] as [from], but no nearer than [CLOSE_DISTANCE]. */
    fun closingPost(from: Point3, defenders: List<Point3>): Point3 {
        val enemy = defenders.minBy { distance(it, from) }
        val range = distance(enemy, from)
        if (range <= CLOSE_DISTANCE) return from
        return short(enemy, from, max(CLOSE_DISTANCE, range / 2)).copy(y = from.y)
    }

    /** [post] moved [offset] blocks horizontally towards [from]. */
    private fun short(post: Point3, from: Point3, offset: Double): Point3 {
        val dx = from.x - post.x
        val dz = from.z - post.z
        val length = hypot(dx, dz)
        if (length < 1.0E-4) return post
        return Point3(post.x + dx / length * offset, post.y, post.z + dz / length * offset)
    }

    /** Whether [threat] is near enough [objective] to be one of its defenders. */
    fun atObjective(threat: Point3, objective: Point3) = distance(threat, objective) <= AttackPhase.OBJECTIVE_RADIUS

    private fun distance(a: Point3, b: Point3) = hypot(a.x - b.x, a.z - b.z)
    private fun distance3(a: Point3, b: Point3) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
}
