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
data class AttackRoles(val assault: Map<UUID, Point3> = emptyMap(), val clearing: Map<UUID, Point3> = emptyMap(), val closing: Map<UUID, ClosingPost> = emptyMap()) {
    companion object {
        val NONE = AttackRoles()
    }
}

/**
 * A firing [post] nearer the defenders, and the [enemy] it was measured from: the man fires at
 * him from there.
 *
 * Aimed at whatever his own target was, a shotgunner on a bound 30 blocks from the nearest
 * defender rejected every post as out of range of a defender 68 blocks off, and never moved
 * (in-game 18.10, #65).
 */
data class ClosingPost(val post: Point3, val enemy: Point3)

/** A member as the operation's attack roles see it. */
data class AttackMember(val id: UUID, val position: Point3, val ready: Boolean)

/**
 * One member's situation for [OperationTactics.duty].
 *
 * @property elsewhere the order's objective is in another dimension.
 * @property inContact the operation has a live contact to fight.
 * @property heightRole the member's role fights from a height post (a machine gunner or sniper).
 * @property primaryAmmo the member still has ammunition for his main weapon.
 * @property reachedMove the member's current MOVE post is reached.
 * @property wasFiring the member's previous intent was FIRE.
 * @property flanking the member is on his way round the objective to his sector, see
 *   [ApproachSectors.arcWaypoint]; [proposal] holds the next waypoint.
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
    val heightRole: Boolean,
    val primaryAmmo: Boolean,
    val hasPatrolRoute: Boolean,
    val reachedMove: Boolean,
    val wasFiring: Boolean,
    val flanking: Boolean = false,
)

data class DutyChoice(val duty: CombatDuty, val anchor: Point3)

/**
 * Each member's duty in the operation and the ways of taking its objective, as geometry and intent
 * rewrites: no world access, no executor state. [AttackPhase] decides when each applies; the HTN operation methods apply it.
 */
object OperationTactics {
    /** Most attackers sent after enemies left around a taken objective. */
    const val MAX_CLEARING_TEAM = 4

    /** A mover this near (horizontally) his front slot has arrived and fires from where he is. */
    const val MOVE_ARRIVAL = 8.0

    /** Closing in, a mover halves its distance to the point's nearest defender, down to this. */
    const val CLOSE_DISTANCE = 20.0

    /**
     * How near (16 blocks) a man in an assault may come to a known enemy. An assault shortens the
     * range and keeps firing; it never runs onto the enemy (#88).
     */
    const val ASSAULT_STANDOFF = 16.0

    /**
     * Roles for [phase]. While defenders still hold the [objective] an assault closes in under
     * fire: each mover takes a firing post half as far from the nearest defender as it stood
     * when the assault began ([origins], see [closingPost]). Once none are left at the point the
     * movers occupy a loose ring inside it; once it is taken, the ready members nearest the
     * [threats] (2 to [MAX_CLEARING_TEAM], keeping those already sent) each go after the nearest
     * one while the rest hold.
     */
    fun roles(phase: AttackPhase, objective: Point3, movers: List<UUID>, members: List<AttackMember>, threats: List<Point3>, previousClearing: Set<UUID>, origins: Map<UUID, Point3> = emptyMap(), standoffFrom: List<Point3> = threats): AttackRoles {
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
            id to keepStandoff(Point3(objective.x + cos(angle) * ring, objective.y, objective.z + sin(angle) * ring), standoffFrom)
        }
        return AttackRoles(assault, clearing)
    }

    /**
     * [slot] pushed out until it is [ASSAULT_STANDOFF] from every one of [threats]. Going onto a
     * point a defender still stands near, the ring would have put men right beside him (#88).
     */
    fun keepStandoff(slot: Point3, threats: List<Point3>): Point3 {
        var point = slot
        repeat(STANDOFF_PASSES) {
            // The nearest first, so the result does not depend on the order contacts are listed in.
            val near = threats.filter { distance(it, point) < ASSAULT_STANDOFF }.minByOrNull { distance(it, point) } ?: return point
            point = if (distance(near, point) < SAME_SPOT) {
                Point3(near.x + ASSAULT_STANDOFF, point.y, near.z)
            } else {
                short(near, point, ASSAULT_STANDOFF).copy(y = point.y)
            }
        }
        return point
    }

    /** Pushing a slot clear of one enemy can bring it near another; this many tries settle it. */
    private const val STANDOFF_PASSES = 4

    private const val SAME_SPOT = 0.01

    /**
     * [mission] with its members' intents rewritten for [roles]. An assault crosses open ground
     * under fire on purpose, so cover is preferred by exposure but not required, and the role
     * clearance from contacts is cut for these members to a short standoff, never lifted (#88).
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

                            // A clearing man shoots the enemy left near the point from a post short of
                            // him: sent to the enemy's own spot, the men ran right up to it (#88).
                            target != null -> intent.copy(duty = CombatDuty.FIRE, anchor = closingPost(intent.origin, listOf(target)).post, focus = target, requireCover = false, requireShelter = false, minimumHeight = null, assault = true)

                            slot != null -> intent.copy(duty = CombatDuty.MOVE, anchor = slot, requireCover = false, requireShelter = false, minimumHeight = null, assault = true)

                            // Closing in is still fire from cover, but inside the role's clearance:
                            // kept to 48 blocks, riflemen never reached a post 20-30 blocks from the
                            // defenders and the assault stood where it began (#54).
                            post != null -> intent.copy(duty = CombatDuty.FIRE, anchor = post.post, focus = post.enemy, minimumHeight = null, assault = true)

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

                // A sentry fights from his height, in defence and in a fight too. Sent to fire from where he
                // stood, a gunner stayed below the tower, and a sentry left it whenever a reported target
                // hid (#73). Out of ammunition for his main weapon he is no sentry.
                (order == TacticalOrder.DEFEND || (order == TacticalOrder.PATROL && !hasPatrolRoute)) && heightRole && primaryAmmo -> CombatDuty.OBSERVE

                !inContact || order in setOf(TacticalOrder.RETREAT, TacticalOrder.MOVE, TacticalOrder.BARRAGE) -> CombatDuty.FOLLOW_ORDER

                order != TacticalOrder.ATTACK -> CombatDuty.FIRE

                // Firing on the way would stop him short of his sector.
                flanking -> CombatDuty.MOVE

                // A mover that reached its post, or already fired, keeps firing instead of moving on.
                // So does one that stands in his slot's area without a post: where no post has a
                // firing lane, in-game 17.10 a flank's movers ran between search posts 10-15 blocks
                // apart round their slot for minutes (#58).
                proposal.duty == CombatDuty.MOVE -> if (reachedMove || wasFiring || distance(position, proposal.anchor) <= MOVE_ARRIVAL) CombatDuty.FIRE else CombatDuty.MOVE

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
    fun closingPost(from: Point3, defenders: List<Point3>): ClosingPost {
        val enemy = defenders.minBy { distance(it, from) }
        val range = distance(enemy, from)
        if (range <= CLOSE_DISTANCE) return ClosingPost(from, enemy)
        return ClosingPost(short(enemy, from, max(CLOSE_DISTANCE, range / 2)).copy(y = from.y), enemy)
    }

    /**
     * Half as far from the nearest of [defenders] as [from], and never nearer than [nearest]: from
     * nearer than that, the post is back at [nearest]. A bound that left the man where he stood
     * kept men 20 blocks from the defenders after an assault was called back (#65).
     */
    fun boundPost(from: Point3, defenders: List<Point3>, nearest: Double): ClosingPost {
        val enemy = defenders.minBy { distance(it, from) }
        return ClosingPost(short(enemy, from, max(nearest, distance(enemy, from) / 2)).copy(y = from.y), enemy)
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
