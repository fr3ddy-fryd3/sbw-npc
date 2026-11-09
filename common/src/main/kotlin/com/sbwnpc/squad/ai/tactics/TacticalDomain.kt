package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationAction
import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * What a member does, and what that means for how his post is searched and held.
 *
 * @property advancing his post must take him on towards its anchor, and counts as reached only
 *   within 4 blocks of it.
 * @property watching once there with no target, he keeps watch over his sector.
 * @property commandsMovement the duty takes over his movement; without it the order's own
 *   behaviour moves him.
 * @property coverUnderFire under incoming fire his post must give cover, see [PostRequirements].
 * @property shelterUnderFire under incoming fire his post must give shelter from its bearing.
 * @property heightPost he fights from a height post round his anchor, with a floor below which
 *   no post counts ([MemberDuties.HEIGHT_POST_DROP]).
 * @property holdsBack his post always hides him from the enemy he faces, and he never closes in:
 *   no closer post, no forward move of his engage. He still fires from where he is.
 */
enum class CombatDuty(
    val advancing: Boolean = false,
    val watching: Boolean = false,
    val commandsMovement: Boolean = true,
    val coverUnderFire: Boolean = false,
    val shelterUnderFire: Boolean = false,
    val heightPost: Boolean = false,
) {
    FIRE(shelterUnderFire = true),
    MOVE(advancing = true, coverUnderFire = true, shelterUnderFire = true),
    REAR_GUARD(watching = true),
    OBSERVE(watching = true, heightPost = true),
    SEARCH(advancing = true, watching = true),
    FOLLOW_ORDER(commandsMovement = false),

    /**
     * A wounded man, or one with nothing to fire, in a sheltered post near where he is ([DutyUtility]).
     * Its post needs no firing lane: with the enemy beyond his reach no post had one, and the
     * last resort's engage walked him into the front row he was to keep out of.
     */
    TAKE_COVER,
    ;

    val holdsBack get() = this == TAKE_COVER
}

/**
 * Where and how one member carries out his [duty].
 *
 * @property hold the last resort once every post search round [anchor] has failed: no post is
 *   searched for, and his order and his gun move him as when no task commands his movement.
 *   Pinned to where he stood instead, a man whose line of fire a friend blocked never stepped
 *   out of it and never fired.
 * @property legs a mover in a fight gets there by legs, from one post on the way to the next,
 *   firing from each before the next rush; see [MemberLeg].
 * @property sightClearance how near the defence his post may lie when it is nearer than his role's
 *   clearance: a blind firing-line slot found sight only there (#93). Null keeps the role's own.
 * @property coveredBy the men whose fire covers his rushes: each rush waits to start until one
 *   of them fires ([TacticalFacts.SUPPORT_FIRING]), however long that takes. Empty, he rushes when ready.
 * @property spread the step off a squadmate's block by a man whose post search found nothing (#299);
 *   its failure is remembered so the step is tried once per failed anchor.
 */
data class PositionIntent(val actor: AgentId, val duty: CombatDuty, val origin: Point3, val anchor: Point3, val focus: Point3?, val requireCover: Boolean = false, val requireShelter: Boolean = false, val minimumHeight: Double? = null, val exposureWeight: Double = 1.0, val inspection: Point3? = null, val elevationWeight: Double = 0.0, val assault: Boolean = false, val standoff: Double = 0.0, val sightClearance: Double? = null, val ordered: Boolean = false, val hold: Boolean = false, val legs: Boolean = false, val coveredBy: Set<java.util.UUID> = emptySet(), val armour: ArmourContact? = null, val holdFeet: Boolean = false, val spread: Boolean = false) {
    init {
        require(actor.level == AgentLevel.NPC && exposureWeight.isFinite() && exposureWeight >= 0 && elevationWeight.isFinite() && elevationWeight >= 0)
    }

    /** A failed-post hold keeps motion; ordinary holds leave it to the order. */
    val commandsMovement get() = duty.commandsMovement && (!hold || holdFeet)

    /**
     * Whether he really goes by [legs]: a mover with an enemy to fire at. An assault keeps running
     * onto its ring or post: its men fire from there, not on the way.
     *
     * The plan and the position action both go by this. The action once ended an assault's run on
     * a post on the way, which the plan, waiting for the assault post, never took as done: the man
     * stood there, "arriving" every tick, until his task changed (in-game 24.10).
     */
    val boundsByLegs get() = legs && duty == CombatDuty.MOVE && !assault && focus != null
}

/** The [number]th leg of a mover's advance by legs ([PositionIntent.legs]): a rush to the next post, then fire from it. */
data class MemberLeg(val intent: PositionIntent, val number: Int)

/**
 * The fire from the post [leg] ended on. Only the actor and the leg's number: a squad revising his
 * intent during the pause (a new focus, cover now required) must not cancel the pause and run the
 * leg again.
 */
data class FirePause(val actor: AgentId, val leg: Int)

/**
 * One turn of a squad's bounding overwatch: the movers in [moving] advance while the rest of the
 * squad covers them from the [posts] it stood on when the turn began, then the halves swap.
 *
 * Fixed for the whole turn, so the firing half's tasks stay the same while it stands.
 */
data class BoundTurn(val number: Int, val moving: Set<java.util.UUID>, val posts: Map<java.util.UUID, Point3>)

/**
 * What a squad keeps for its strategy to shape the members' tasks with, fixed while the strategy
 * holds so the tasks stay the same.
 *
 * @property turn the bounding turn under [CombatStrategy.BOUND_OVERWATCH].
 * @property rally where the squad gathers under [CombatStrategy.REGROUP].
 * @property retreating it falls back on a RETREAT order (#253).
 */
data class SquadShaping(val turn: BoundTurn? = null, val rally: Point3? = null, val retreating: Boolean = false, val manoeuvre: AgentId? = null)

/**
 * What the squad gives a fire team to do (#184). The team decides how: who fires from where and
 * how its men rush.
 */
enum class TeamRole {
    /** Every man does the duty his squad gave him, as one team. */
    ALL,

    /** The team fires to cover the squad's moving team, from where it stands. */
    SUPPORT,

    /** The team moves: round a flank, or the moving half of a bounding squad. */
    MANOEUVRE,

    /** The team watches the squad's rear and sides. */
    GUARD,
}

/**
 * A fire team's [members] and the [role] its squad gave it.
 *
 * @property covering the men whose fire covers a manoeuvring team's rushes (the bounding squad's
 *   supporting teams). Empty, the team covers its own rushes.
 * @property posts where a supporting team's men fire from while the squad bounds: where they stood
 *   when the turn began. Empty, they keep the posts the squad gave them.
 */
data class TeamMission(val agent: AgentId, val members: List<PositionIntent>, val role: TeamRole = TeamRole.ALL, val covering: Set<java.util.UUID> = emptySet(), val posts: Map<java.util.UUID, Point3> = emptyMap())

/**
 * The squad's choice of each team's [TeamRole] (#184). A bounding squad's moving team manoeuvres
 * covered by the others, which support from their turn posts; a flanking squad's manoeuvre team
 * goes round while the others support head-on; a team that only watches guards; otherwise all.
 */
object TeamRoles {
    fun assign(teams: List<TeamMission>, shaping: SquadShaping): List<TeamMission> {
        val turn = shaping.turn
        val split = turn != null || teams.any { it.agent == shaping.manoeuvre }
        fun moves(team: TeamMission) = if (turn != null) team.members.any { it.actor.id in turn.moving } else team.agent == shaping.manoeuvre
        val covering = teams.filter { !moves(it) && !watching(it) }.flatMapTo(HashSet()) { team -> team.members.map { it.actor.id } }
        return teams.map { team ->
            when {
                // A team that only watches guards, unless it is the one the squad moves; it covers nobody.
                watching(team) && !moves(team) -> team.copy(role = TeamRole.GUARD)

                !split -> team

                moves(team) -> team.copy(role = TeamRole.MANOEUVRE, covering = if (turn != null) covering else emptySet())

                else -> team.copy(role = TeamRole.SUPPORT, posts = turn?.posts?.filterKeys { id -> team.members.any { it.actor.id == id } }.orEmpty())
            }
        }
    }

    private fun watching(team: TeamMission) = team.members.isNotEmpty() && team.members.all { it.duty.watching }

    /**
     * [mission]'s men as a supporting team fires: its movers fire from their [TeamMission.posts]
     * instead of rushing. No cover required there: on open ground it refused every post and the
     * team never fired. Without posts (supporting a flank) the squad's own posts stand.
     */
    fun support(mission: TeamMission): TeamMission {
        if (mission.posts.isEmpty()) return mission
        return mission.copy(
            members = mission.members.map { intent ->
                if (!rushes(intent)) {
                    intent
                } else {
                    intent.copy(duty = CombatDuty.FIRE, anchor = mission.posts[intent.actor.id] ?: intent.origin, requireCover = false, legs = false)
                }
            },
        )
    }

    /**
     * [mission]'s movers as a team covered by others rushes: each rush waits for their fire
     * ([PositionIntent.coveredBy]), and no shelter is needed at the end of a leg: kept, it refused
     * every post on open ground and the team never moved.
     */
    // A man of the team who rushes by legs for it; assault and ordered men keep their own runs.
    private fun rushes(intent: PositionIntent) = intent.legs && intent.duty == CombatDuty.MOVE && !intent.assault && !intent.ordered

    fun covered(mission: TeamMission): TeamMission =
        mission.copy(
            members = mission.members.map { intent ->
                if (!rushes(intent)) intent else intent.copy(requireCover = false, requireShelter = false, coveredBy = mission.covering)
            },
        )
}

/** A squad's [teams] of ground men, its mortar crews ([support], #258), its helicopters ([air], #260) and its tanks ([tanks], #185), which the squad's strategy never shapes. */
data class SquadMission(val agent: AgentId, val teams: List<TeamMission>, val support: List<SupportCrew> = emptyList(), val air: List<Aircraft> = emptyList(), val tanks: List<Tank> = emptyList())

/** A squad's [mission] under the [strategy] its method chose, for the choice of its [SquadApproach]. */
data class StrategyMission(val mission: SquadMission, val strategy: CombatStrategy)

/** How a squad attacking alone comes at its objective. */
enum class SquadApproach {
    /** A manoeuvre group goes round to a flank along the arc while the rest fixes the enemy with fire. */
    FLANK,

    /** The whole squad comes on from where it is. */
    FRONTAL,
}

/** Rejects the ordinary coordination of an operation that calls its attack off or weighs its losses. */
private fun steady(mission: OperationMission) =
    Condition {
        when {
            mission.callOff != null -> Verdict.Rejected("calling_off")
            mission.review -> Verdict.Rejected("reviewing")
            else -> Verdict.Allowed
        }
    }

data class AreaControlTask(val operation: java.util.UUID, val objective: Point3, val commands: Map<java.util.UUID, java.util.UUID>)

/**
 * Where a called-off squad falls back to: [point], a Supply when [toSupply].
 */
data class FallBack(val point: Point3, val toSupply: Boolean)

/**
 * An attack the operation calls off by its strength (#252, the rules of #238): its counted
 * [squads] fall back each to its [fallBacks] point, with [able] of the [base] men they went in with.
 * With [partial] the squads left out of the count fight on, and only these leave the operation.
 */
data class CallOff(val operation: java.util.UUID, val squads: List<java.util.UUID>, val able: Int, val base: Int, val partial: Boolean, val fallBacks: Map<java.util.UUID, FallBack>)

/** A travelling squad's [seats] in its ground vehicles ([SeatAllocation], #255), given out by its plan. */
data class Boarding(val squad: java.util.UUID, val seats: SeatPlan)

/** A team's men sent to the vehicle guns its [plan] gave them ([WeaponStations], #255). */
data class StationManning(val team: AgentId, val plan: StationPlan)

/**
 * What an operation's plan carries out: its [squads]' missions, the area it [secure]s and its
 * [attack] roles; with [callOff] the attack it calls off instead, and with [review] an attack that
 * holds the line it has while its losses are weighed (#252).
 */
data class OperationMission(val squads: List<SquadMission>, val secure: AreaControlTask? = null, val attack: AttackRoles = AttackRoles.NONE, val callOff: CallOff? = null, val review: Boolean = false)

/**
 * Where fire on a man comes from, rounded to [GRID] blocks so the rounds of one shooter keep the
 * same value and do not replan him on every hit. The integration also keeps a sector while the fire
 * stays near where it was taken, so a point on a cell boundary does not flip it either.
 */
data class FireSector(val x: Int, val z: Int) {
    companion object {
        const val GRID = 8

        fun of(x: Double, z: Double) = FireSector(Math.floorDiv(x.toInt(), GRID), Math.floorDiv(z.toInt(), GRID))
    }
}

/** No cover was found or reached from fire out of [sector] at [failedAt]. */
data class CoverFailure(val sector: FireSector, val failedAt: Long)

/** Walk to a point hidden from fire out of [sector], or, with [fallBack], away from it. */
data class CoverMove(val actor: AgentId, val sector: FireSector, val fallBack: Boolean, val armour: ArmourContact? = null)

/** The [number]th dwell-and-peek from the cover he holds. */
data class CoverCycle(val actor: AgentId, val number: Int, val armour: ArmourContact? = null)

/** Dig the [number]th block of a foxhole where he stands. */
data class DigIn(val actor: AgentId, val number: Int)

/** Stay in the hole until he is hit again or healed. */
data class HoldHole(val actor: AgentId, val digs: Int)

/** Climb out of the hole he dug. */
data class LeaveHole(val actor: AgentId)

/**
 * The post searches round [anchor] that failed; [spreadTried] once the step off a squadmate failed there too.
 * Typed observations are scoped by FactSnapshot.owner; no world queries live in this domain.
 */
data class LocalPositionFailure(val anchor: Point3, val failedAt: Long, val attempts: Int, val rejections: Map<String, Int>, val spreadTried: Boolean = false)

object TacticalFacts {
    val POST_FAILURE = FactKey("post.local_failure", LocalPositionFailure::class.java)
    val AREA_SECURED = FactKey("operation.area_secured", Boolean::class.javaObjectType)

    /** A squadmate stands within [com.sbwnpc.squad.combat.FiringSpots.MIN_SPACING] of him (#299). */
    val CROWDED = FactKey("actor.crowded", Boolean::class.javaObjectType)
    val MOVABLE = FactKey("actor.movable", Boolean::class.javaObjectType)
    val COVER = FactKey("sector.actual_cover", Boolean::class.javaObjectType)
    val ARRIVED = FactKey("post.actual_arrival", Boolean::class.javaObjectType)

    /** He stands on the post his position action found, the anchor's or one on the way to it. */
    val AT_POST = FactKey("post.at_found_post", Boolean::class.javaObjectType)

    /** How many legs of an advance by legs he has finished, fire included. */
    val LEGS_DONE = FactKey("actor.legs_done", Int::class.javaObjectType)

    /** The squad's fight as its strategy methods weigh it. */
    val SITUATION = FactKey("squad.situation", CombatSituation::class.java)

    /** The strategy of the squad's installed plan, absent before its first. */
    val MANNER = FactKey("squad.manner", CombatStrategy::class.java)

    /** Present while the [MANNER] is too young to give up for a better one, see [SquadManners]. */
    val MANNER_LOCK = FactKey("squad.manner_lock", Boolean::class.javaObjectType)

    /** The squad attacks alone, can split off a manoeuvre group, is not scattered or regrouping, and has not lately found the flank blocked. */
    val FLANK_OPEN = FactKey("squad.flank_open", Boolean::class.javaObjectType)

    /** The approach of the squad's installed plan, absent before its first. */
    val APPROACH = FactKey("squad.approach", SquadApproach::class.java)

    /** The squad's current turn while it advances by [CombatStrategy.BOUND_OVERWATCH]. */
    val BOUND_TURN = FactKey("squad.bound_turn", BoundTurn::class.java)

    /** What an attack of two groups scores its manoeuvre on (#173), measured once at first contact; absent otherwise. */
    val MANOEUVRE_INPUT = FactKey("operation.manoeuvre_input", ManoeuvreAssessment::class.java)

    /** The manoeuvre of the operation's installed plan, absent before its first. */
    val MANOEUVRE = FactKey("operation.manoeuvre", OperationManoeuvre::class.java)

    /** The fire team a squad attacking alone sends round its flank (#184), absent while it does not split. */
    val MANOEUVRE_TEAM = FactKey("squad.manoeuvre_team", AgentId::class.java)

    /** Where the squad gathers while it regroups under [CombatStrategy.REGROUP]. */
    val RALLY = FactKey("squad.rally", Point3::class.java)

    /** The squad travels to its objective instead of fighting where it is ([SquadJourney], #255). */
    val TRAVELLING = FactKey("squad.travelling", Boolean::class.javaObjectType)

    /** A hidden recon squad is on its approach to an observation post (#194): no strategy shapes it. */
    val RECONNOITRING = FactKey("squad.reconnoitring", Boolean::class.javaObjectType)

    /** Who of a team in a fight mans which empty armed vehicle's gun ([WeaponStations]). */
    val STATIONS = FactKey("team.stations", StationPlan::class.java)

    /** Who of a travelling squad rides in which of its ground vehicles, and who walks ([SeatAllocation]). */
    val SEATS = FactKey("squad.seats", SeatPlan::class.java)

    /**
     * The team's squad waited too long for covering fire for a rush ([CombatSituation.coverStalled]):
     * its men no longer rush in pairs.
     */
    val COVER_STALLED = FactKey("team.cover_stalled", Boolean::class.javaObjectType)

    /** The men covering his rush are firing; see [PositionIntent.coveredBy]. */
    val SUPPORT_FIRING = FactKey("actor.support_firing", Boolean::class.javaObjectType)
    val ATTACK_PHASE = FactKey("operation.attack_phase", AttackPhase::class.java)

    /** He is under fire (suppressed). Observed every tick, true or false, so methods that exclude it never wait on it. */
    val UNDER_FIRE = FactKey("actor.under_fire", Boolean::class.javaObjectType)

    /** Where the fire comes from while [UNDER_FIRE]. */
    val FIRE_SECTOR = FactKey("actor.fire_sector", FireSector::class.java)

    /** His last search for cover from that fire found none or could not reach it. */
    val COVER_FAILURE = FactKey("actor.cover_failure", CoverFailure::class.java)

    /** He stands where the fire cannot see him, as his cover move found. */
    val SHELTERED = FactKey("actor.sheltered", Boolean::class.javaObjectType)

    /** How many dwell-and-peeks he has finished in cover; each one makes him replan. */
    val COVER_CYCLES = FactKey("actor.cover_cycles", Int::class.javaObjectType)

    /** He has a live target he sees, or one not shooting at him now: the case where a man without cover holds and fires. */
    val ENGAGED = FactKey("actor.engaged", Boolean::class.javaObjectType)

    /** Digging in is possible where he stands: badly hurt, a squadmate covering, flat diggable ground. */
    val DIG_POSSIBLE = FactKey("actor.dig_possible", Boolean::class.javaObjectType)

    /** Blocks of foxhole he has dug where he is; zero when he is in none. */
    val DIGS = FactKey("actor.digs", Int::class.javaObjectType)

    /** He was hit since he last finished digging. */
    val HIT_IN_HOLE = FactKey("actor.hit_in_hole", Boolean::class.javaObjectType)

    /** He has healed past the health that let him dig in. */
    val HEALED = FactKey("actor.healed", Boolean::class.javaObjectType)

    // His engage (#251 step B), see Engage: observed every tick for every planned man, true or false.

    /** He has a live target and is not marching on with his squad: he fights where he is. */
    val IN_CONTACT = FactKey("actor.in_contact", Boolean::class.javaObjectType)

    /** He fights with his pistol. */
    val SIDEARM = FactKey("actor.sidearm", Boolean::class.javaObjectType)

    /** He is a downed pilot and an enemy is nearer than he keeps away from, with a band of [Engage.BAND] on the way out. */
    val KEEP_AWAY = FactKey("actor.keep_away", Boolean::class.javaObjectType)

    /** His target is within his stopping distance, with a band of [Engage.BAND] on the way out. */
    val IN_REACH = FactKey("actor.in_reach", Boolean::class.javaObjectType)

    /** He had a clear shot at his target within [Engage.CLEAR_SHOT_TICKS]. */
    val CLEAR_SHOT = FactKey("actor.clear_shot", Boolean::class.javaObjectType)

    /** His squad's order holds a position (DEFEND, PATROL). */
    val HOLDING_ORDER = FactKey("actor.holding_order", Boolean::class.javaObjectType)

    /** A defender stands outside his area, with a band of [Engage.BAND] on the way back in. */
    val OUTSIDE_AREA = FactKey("actor.outside_area", Boolean::class.javaObjectType)

    /** Holding, his target is far enough off for one bounded approach, and he has not made it yet from this post. */
    val MAY_ADVANCE = FactKey("actor.may_advance", Boolean::class.javaObjectType)

    /** An ally has stood in his line of fire for [Engage.LANE_DWELL_CHECKS] lane checks in a row. */
    val LANE_BLOCKED_ALLY = FactKey("actor.lane_blocked_ally", Boolean::class.javaObjectType)

    /** A vehicle hull has stood in his line of fire for [Engage.LANE_DWELL_CHECKS] lane checks in a row. */
    val LANE_BLOCKED_HULL = FactKey("actor.lane_blocked_hull", Boolean::class.javaObjectType)

    /**
     * His current target is fresh: within [Engage.OPENING_WINDOW] of his first shootable line to it,
     * and his opening burst is not fired yet.
     */
    val OPENING_DUE = FactKey("actor.opening_due", Boolean::class.javaObjectType)

    /** He walks off to resupply or withdraws: the ammunition rules have his feet. */
    val RESUPPLYING = FactKey("actor.resupplying", Boolean::class.javaObjectType)

    /** Engage units he finished; each one makes him replan, as [COVER_CYCLES] do. */
    val ENGAGE_UNITS = FactKey("actor.engage_units", Int::class.javaObjectType)
}

/**
 * A travelling squad's seats once they are worked out, for its ground men and its crews on foot
 * alike: a squad of crews alone that set off by road waited for seats nobody gave out (#255).
 */
private fun boarding(mission: SquadMission, facts: FactView) = facts.value(TacticalFacts.SEATS)?.takeIf { facts.value(TacticalFacts.TRAVELLING) == true }?.let { NetworkNode("board", TacticalDomain.BOARD_TASK.call(Boarding(mission.agent.id, it))) }

object TacticalDomain {
    val OPERATION = TaskKey("operation.execute", OperationMission::class.java)
    val SQUAD = TaskKey("squad.execute", SquadMission::class.java)
    val TEAM = TaskKey("team.execute", TeamMission::class.java)
    val NPC = TaskKey("npc.execute", PositionIntent::class.java)
    val SECURE = ActionKey("operation.secure_area", AreaControlTask::class.java)

    /** Calling an attack off (#252), carried out by the integration. */
    val CALL_OFF = ActionKey("operation.call_off", CallOff::class.java)
    private val CALL_OFF_TASK = TaskKey("operation.call_off", CallOff::class.java)
    private val SECURE_TASK = TaskKey("operation.hold_and_inspect", AreaControlTask::class.java)
    val POSITION = ActionKey("npc.position", PositionIntent::class.java)

    /** Fire from the post a leg ended on, until he has fired or found nothing to fire at. */
    val FIRE_PAUSE = ActionKey("npc.fire_pause", FirePause::class.java)
    private val DELEGATE = TaskKey("delegate.task", DelegationTask::class.java)
    private val APPROACH = TaskKey("squad.approach", StrategyMission::class.java)

    /** Sending a team's men to the guns its plan gave them (#255), carried out by the integration. */
    val MAN_STATIONS = ActionKey("team.man_stations", StationManning::class.java)
    private val MAN_STATIONS_TASK = TaskKey("team.man_stations", StationManning::class.java)

    /** Giving a travelling squad's men their seats (#255), carried out by the integration. */
    val BOARD = ActionKey("squad.board", Boarding::class.java)
    internal val BOARD_TASK = TaskKey("squad.board", Boarding::class.java)

    private fun delegate() =
        PrimitiveTask(DELEGATE) { task ->
            PrimitiveSpec(task.child.id.toString(), ActionSpec(DelegationAction.KEY, task.copy(continuous = true), setOf(ResourceKey(task.child, "command")), timeoutTicks = Long.MAX_VALUE), TaskContract())
        }

    private fun children(tasks: List<DelegationTask>) = TaskNetwork(tasks.map { NetworkNode(it.child.id.toString(), DELEGATE.call(it)) })

    /** The mortar crew's tasks, joined into the team and NPC domains below. */
    private val crewTasks = FireSupport.tasks(::children)

    /** The helicopter's tasks, joined the same way. */
    private val airTasks = AirSupport.tasks(::children)

    /** The tank's tasks, joined the same way. */
    private val tankTasks = TankSupport.tasks(::children)
    private fun coordinate(mission: OperationMission, roles: AttackRoles): TaskNetwork {
        val nodes = mission.squads.map { squad -> NetworkNode(squad.agent.id.toString(), DELEGATE.call(DelegationTask(squad.agent, SQUAD.call(OperationTactics.apply(squad, roles))))) }
        return TaskNetwork(nodes + listOfNotNull(mission.secure?.let { NetworkNode("secure", SECURE_TASK.call(it)) }))
    }

    private val MANOEUVRE_TASK = TaskKey("operation.manoeuvre", OperationMission::class.java)

    /** The call-off is done in its first tick; this only bounds a refused one. */
    private const val CALL_OFF_TICKS = 20L

    val operation = HtnDomain(
        AgentLevel.OPERATION,
        listOf(
            CompoundTask(
                OPERATION,
                // Without an attack phase the squads carry out their own missions; an attacking
                // operation deploys a firing line, assaults into its objective or clears around it.
                // An attack that has lost too much is called off before anything else; one weighing
                // its losses holds the line it has (its squads were laid out holding) whatever its
                // phase. Each stands the later ones aside: methods of equal score are tried by name.
                listOf(
                    Method<OperationMission>("withdraw", eligible = { mission ->
                        Condition { if (mission.callOff != null) Verdict.Allowed else Verdict.Rejected("strength_holds") }
                    }, decompose = { mission, _ -> TaskNetwork.sequence(CALL_OFF_TASK.call(mission.callOff!!)) }),
                    Method<OperationMission>("review", eligible = { mission ->
                        Condition { if (mission.review && mission.callOff == null) Verdict.Allowed else Verdict.Rejected("not_reviewing") }
                    }, decompose = { mission, _ -> coordinate(mission, mission.attack) }),
                    Method<OperationMission>("coordinate", eligible = { mission ->
                        Condition.all(steady(mission), Condition { facts -> if (facts.value(TacticalFacts.ATTACK_PHASE) == null) Verdict.Allowed else Verdict.Rejected("attack_phase_known") })
                    }, decompose = { mission, _ -> coordinate(mission, AttackRoles.NONE) }),
                ) + AttackPhase.entries.map { phase ->
                    Method<OperationMission>(phase.name.lowercase(), eligible = { mission ->
                        Condition.all(steady(mission), Condition.test(TacticalFacts.ATTACK_PHASE, "other_attack_phase") { it == phase })
                    }, decompose = { mission, _ -> TaskNetwork.sequence(MANOEUVRE_TASK.call(mission)) })
                },
            ),
            // How the attack comes at its objective (#173): the manoeuvre shows in the next layout's
            // sectors; every method hands the squads the same tasks. A held one keeps an advantage.
            CompoundTask(
                MANOEUVRE_TASK,
                OperationManoeuvre.entries.map { option ->
                    Method<OperationMission>(
                        OperationManoeuvres.methodId(option),
                        eligible = { Condition { facts -> OperationManoeuvres.eligible(option, facts.value(TacticalFacts.MANOEUVRE_INPUT)).evaluate(facts) } },
                        score = { _, facts ->
                            val score = OperationManoeuvres.score(option, facts.value(TacticalFacts.MANOEUVRE_INPUT))
                            if (facts.value(TacticalFacts.MANOEUVRE) == option && score.value > 0) score.copy(value = score.value + SquadManners.ADVANTAGE) else score
                        },
                        decompose = { mission, _ -> coordinate(mission, mission.attack) },
                    )
                },
            ),
            delegate(),
            PrimitiveTask(CALL_OFF_TASK) { callOff ->
                PrimitiveSpec("call_off", ActionSpec(CALL_OFF, callOff, timeoutTicks = CALL_OFF_TICKS), TaskContract())
            },
            PrimitiveTask(SECURE_TASK) { task ->
                PrimitiveSpec(
                    "secure",
                    ActionSpec(SECURE, task, timeoutTicks = Long.MAX_VALUE),
                    TaskContract(completed = Condition.test(TacticalFacts.AREA_SECURED, "area_not_held_and_checked") { it }),
                )
            },
        ),
    )
    val squad = HtnDomain(
        AgentLevel.SQUAD,
        listOf(
            CompoundTask(
                SQUAD,
                // A squad of crews or aircraft alone has no ground men to shape: it only supports.
                listOf(
                    Method<SquadMission>("support_only", eligible = { mission ->
                        Condition { if (mission.teams.isEmpty() && (mission.support + mission.air + mission.tanks).isNotEmpty()) Verdict.Allowed else Verdict.Rejected("has_ground_teams") }
                    }, decompose = { mission, facts -> TaskNetwork(children(FireSupport.delegations(mission) + AirSupport.delegations(mission) + TankSupport.delegations(mission)).nodes + listOfNotNull(boarding(mission, facts))) }),
                    // A hidden recon squad stalks to its observation post (#194): its men's stages are
                    // ordered duties, as a travelling squad's are, and no strategy shapes them.
                    Method<SquadMission>("reconnoitre", eligible = { mission ->
                        Condition { facts -> if (mission.teams.isNotEmpty() && facts.value(TacticalFacts.RECONNOITRING) == true) Verdict.Allowed else Verdict.Rejected("not_reconnoitring") }
                    }, decompose = { mission, _ -> children(mission.teams.map { DelegationTask(it.agent, TEAM.call(it)) }) }),
                    // A squad travelling to its objective (#255) is no squad in a fight: its men make
                    // for the vehicles or the objective as their order says, and no strategy shapes them.
                    Method<SquadMission>("travel", eligible = { mission ->
                        Condition { facts -> if (mission.teams.isNotEmpty() && facts.value(TacticalFacts.TRAVELLING) == true) Verdict.Allowed else Verdict.Rejected("not_travelling") }
                    }, decompose = { mission, facts ->
                        val teams = children(mission.teams.map { DelegationTask(it.agent, TEAM.call(it)) } + FireSupport.delegations(mission) + AirSupport.delegations(mission) + TankSupport.delegations(mission))
                        TaskNetwork(teams.nodes + listOfNotNull(boarding(mission, facts)))
                    }),
                ) + CombatStrategy.entries.map { strategy ->
                    Method(
                        SquadManners.methodId(strategy),
                        eligible = { mission ->
                            when {
                                mission.teams.isEmpty() -> Condition { Verdict.Rejected("no_ground_teams") }

                                else -> Condition.all(
                                    Condition { facts ->
                                        when {
                                            facts.value(TacticalFacts.TRAVELLING) == true -> Verdict.Rejected("travelling")
                                            facts.value(TacticalFacts.RECONNOITRING) == true -> Verdict.Rejected("reconnoitring")
                                            else -> Verdict.Allowed
                                        }
                                    },
                                    SquadManners.eligible(strategy),
                                )
                            }
                        },
                        score = { _, facts -> SquadManners.score(strategy, facts) },
                        decompose = { mission, _ -> TaskNetwork.sequence(APPROACH.call(StrategyMission(mission, strategy))) },
                    )
                },
            ),
            // The approach only shows in the next layout, which splits off a manoeuvre group for a
            // flank or not; both methods hand the teams the same tasks.
            CompoundTask(
                APPROACH,
                SquadApproach.entries.map { approach ->
                    Method(
                        SquadApproaches.methodId(approach),
                        eligible = { SquadApproaches.eligible(approach) },
                        score = { _, facts -> SquadApproaches.score(approach, facts) },
                        decompose = { (mission, strategy), facts ->
                            // Only a bounding squad has a turn and only a regrouping one a rally; otherwise the facts are
                            // absent and never change.
                            val shaping = SquadShaping(
                                facts.value(TacticalFacts.BOUND_TURN).takeIf { strategy == CombatStrategy.BOUND_OVERWATCH },
                                facts.value(TacticalFacts.RALLY),
                                facts.value(TacticalFacts.SITUATION)?.retreating == true,
                                facts.value(TacticalFacts.MANOEUVRE_TEAM).takeIf { approach == SquadApproach.FLANK },
                            )
                            val shaped = mission.teams.map { team ->
                                team.copy(
                                    // A member assaulting for the operation keeps its role whatever the squad's own strategy,
                                    // and so does one moving for his order itself: HOLD turned a following squad's bounds
                                    // back into fire from where it stood (#116).
                                    members = team.members.map { intent -> if (intent.assault || intent.ordered) intent else strategy.rules.shape(intent, shaping) },
                                )
                            }
                            val tasks = TeamRoles.assign(shaped, shaping).map { DelegationTask(it.agent, TEAM.call(it)) }
                            children(tasks + FireSupport.delegations(mission) + AirSupport.delegations(mission) + TankSupport.delegations(mission))
                        },
                    )
                },
            ),
            delegate(),
            // Never done by itself: the seats hold while the squad travels, and go when it stops.
            PrimitiveTask(BOARD_TASK) { boarding ->
                PrimitiveSpec("board", ActionSpec(BOARD, boarding, timeoutTicks = Long.MAX_VALUE), TaskContract())
            },
        ),
    )

    // All men or a flank's manoeuvre team rush on their own fire; a bounding squad's moving team on its supporters'.
    private val selfCovering = { mission: TeamMission -> mission.role == TeamRole.ALL || (mission.role == TeamRole.MANOEUVRE && mission.covering.isEmpty()) }
    private val hasRole = { mission: TeamMission, role: TeamRole -> Condition { if (mission.role == role) Verdict.Allowed else Verdict.Rejected("role_${mission.role.name.lowercase()}") } }
    private val teamNetwork = { mission: TeamMission, facts: FactView ->
        val men = children(mission.members.map { DelegationTask(it.actor, NPC.call(it)) })
        // Only while its squad fights and its order lets a man leave his place for a gun.
        val guns = facts.value(TacticalFacts.STATIONS)?.let { NetworkNode("man_stations", MAN_STATIONS_TASK.call(StationManning(mission.agent, it))) }
        TaskNetwork(men.nodes + listOfNotNull(guns))
    }
    val team = HtnDomain(
        AgentLevel.TEAM,
        listOf(
            CompoundTask(
                TEAM,
                // A team covering its own rushes chooses how; a supporting, covered or guarding one has
                // one way to carry its role out.
                TeamRush.entries.map { rush ->
                    Method<TeamMission>(
                        rush.name.lowercase(),
                        eligible = { mission -> if (selfCovering(mission)) TeamRushes.eligible(rush, mission) else Condition { Verdict.Rejected("role_${mission.role.name.lowercase()}") } },
                        score = { mission, _ -> TeamRushes.score(rush, mission) },
                        decompose = { mission, facts -> teamNetwork(TeamRushes.shape(rush, mission), facts) },
                    )
                } + listOf(
                    Method<TeamMission>("support", eligible = { mission -> hasRole(mission, TeamRole.SUPPORT) }, decompose = { mission, facts -> teamNetwork(TeamRoles.support(mission), facts) }),
                    Method<TeamMission>("manoeuvre_covered", eligible = { mission ->
                        Condition { if (mission.role == TeamRole.MANOEUVRE && mission.covering.isNotEmpty()) Verdict.Allowed else Verdict.Rejected("not_covered_manoeuvre") }
                    }, decompose = { mission, facts -> teamNetwork(TeamRoles.covered(mission), facts) }),
                    Method<TeamMission>("guard", eligible = { mission -> hasRole(mission, TeamRole.GUARD) }, decompose = { mission, facts -> teamNetwork(mission, facts) }),
                ),
            ),
            delegate(),
            PrimitiveTask(MAN_STATIONS_TASK) { manning ->
                PrimitiveSpec("man_stations", ActionSpec(MAN_STATIONS, manning, timeoutTicks = Long.MAX_VALUE), TaskContract())
            },
        ) + crewTasks.first + airTasks.first + tankTasks.first,
    )

    val COVER_MOVE = ActionKey("npc.cover_move", CoverMove::class.java)
    val COVER_CYCLE = ActionKey("npc.cover_cycle", CoverCycle::class.java)
    val DIG = ActionKey("npc.dig", DigIn::class.java)
    val HOLD_HOLE = ActionKey("npc.hold_hole", HoldHole::class.java)
    val LEAVE_HOLE = ActionKey("npc.leave_hole", LeaveHole::class.java)
    private val UNDER_FIRE = TaskKey("npc.under_fire", PositionIntent::class.java)
    private val IN_HOLE = TaskKey("npc.in_hole", PositionIntent::class.java)
    private val COVER_MOVE_TASK = TaskKey("npc.cover_move", CoverMove::class.java)
    private val COVER_CYCLE_TASK = TaskKey("npc.cover_cycle", CoverCycle::class.java)
    private val DIG_TASK = TaskKey("npc.dig", DigIn::class.java)
    private val HOLD_HOLE_TASK = TaskKey("npc.hold_hole", HoldHole::class.java)
    private val LEAVE_HOLE_TASK = TaskKey("npc.leave_hole", LeaveHole::class.java)
    private val POST = TaskKey("npc.post", PositionIntent::class.java)
    private val LEG = TaskKey("npc.leg", MemberLeg::class.java)
    private val PAUSE = TaskKey("npc.fire_pause", FirePause::class.java)

    /** A man in contact whose movement nothing else commands. */
    val ENGAGE_TASK = TaskKey("npc.engage", EngageTask::class.java)

    /** The root of an engage-only agent: a grenade his squad lets him throw, else his engage (#263). */
    val FIGHT_TASK = TaskKey("npc.fight", EngageTask::class.java)

    /** One unit of the move his engage chose, see [EngageUnit]. */
    val ENGAGE = ActionKey("npc.engage_unit", EngageUnit::class.java)
    private val ENGAGE_UNIT = TaskKey("npc.engage_unit", EngageUnit::class.java)
    private val LEG_UNIT = TaskKey("npc.leg_unit", MemberLeg::class.java)
    private val POSITION_UNIT = TaskKey("npc.position_unit", PositionIntent::class.java)
    val npc = HtnDomain(
        AgentLevel.NPC,
        listOf(
            CompoundTask(
                NPC,
                listOf(
                    // A grenade his squad lets him throw comes before his duty and his engage
                    // (#263); a man under fire or in his hole answers that first unless the
                    // grenade is for the fire itself (Grenades.throws).
                    Method("grenade", eligible = { intent ->
                        Condition { facts ->
                            // An operator about to launch or flying his drone keeps to it.
                            DroneStrike.standsAside(facts)?.let { return@Condition it }
                            val underFire = UnderFire.eligible(intent).evaluate(facts).takeIf { it == Verdict.Allowed } ?: UnderFire.inHole().evaluate(facts)
                            Grenades.throws(facts, underFire)
                        }
                    }, decompose = { intent, facts -> TaskNetwork.sequence(Grenades.next(intent.actor, facts)) }),
                    // Under fire, or in the hole he dug, the man answers that first; every method
                    // below stands aside meanwhile (UnderFire.duty), so exactly one family applies.
                    // A drone in the air keeps him at its monitor through all of it (DroneStrike.flying).
                    Method<PositionIntent>("answer_armour", eligible = { intent -> Condition { facts -> if (ArmourResponse.answers(intent, facts) && !ArmourResponse.launches(facts)) Verdict.Allowed else Verdict.Rejected("no_armour_response") } }, decompose = { intent, _ -> TaskNetwork.sequence(ArmourResponse.TASK.call(intent)) }),
                    Method("under_fire", eligible = { intent -> Condition { facts -> DroneStrike.flying(facts) ?: UnderFire.eligible(intent).evaluate(facts) } }, decompose = { intent, _ -> TaskNetwork.sequence(UNDER_FIRE.call(intent)) }),
                    Method("in_hole", eligible = { _ -> Condition { facts -> DroneStrike.flying(facts) ?: UnderFire.inHole().evaluate(facts) } }, decompose = { intent, _ -> TaskNetwork.sequence(IN_HOLE.call(intent)) }),
                    // A drone operator flies his drone, or launches one when not under fire, ahead
                    // of his duty and his engage (#259).
                    Method("drone_strike", eligible = { intent ->
                        Condition { facts ->
                            if (ArmourResponse.answers(intent, facts) && !ArmourResponse.launches(facts)) return@Condition Verdict.Rejected("answer_armour")
                            if (DroneStrike.flying(facts) == null) UnderFire.duty(intent, facts)?.let { return@Condition it }
                            DroneStrike.takesOver().evaluate(facts)
                        }
                    }, decompose = { intent, _ -> TaskNetwork.sequence(DroneStrike.TASK.call(StrikeCall(intent.actor))) }),
                    // In contact, with nothing commanding his movement, his engage moves him; every
                    // other method below stands aside meanwhile (NpcFamilies.standsAside).
                    Method("engage", eligible = { intent ->
                        Condition { facts ->
                            DroneStrike.flying(facts)?.let { return@Condition it }
                            ArmourResponse.standsAside(intent, facts)?.let { return@Condition it }
                            UnderFire.duty(intent, facts)?.let { return@Condition it }
                            DroneStrike.standsAside(facts)?.let { return@Condition it }
                            Engage.eligible(intent).evaluate(facts)
                        }
                    }, decompose = { intent, _ -> TaskNetwork.sequence(ENGAGE_TASK.call(EngageTask(intent.actor))) }),
                    // A mover in a fight rushes from cover to cover and fires from each, rather than
                    // running the whole way to his slot under fire.
                    Method("fire_and_bound", eligible = { intent ->
                        Condition { facts ->
                            NpcFamilies.standsAside(intent, facts)?.let { return@Condition it }
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                !intent.boundsByLegs -> Verdict.Rejected("not_advancing_by_legs")
                                facts.sidearm -> Verdict.Rejected("sidearm")
                                failure?.anchor == intent.anchor -> Verdict.Rejected("preferred_query_exhausted")
                                else -> Verdict.Allowed
                            }
                        }
                    }, decompose = { intent, facts ->
                        val leg = MemberLeg(intent, facts.value(TacticalFacts.LEGS_DONE) ?: 0)
                        TaskNetwork.sequence(LEG.call(leg), PAUSE.call(FirePause(intent.actor, leg.number)))
                    }),
                    Method("preferred_post", eligible = { intent ->
                        Condition { facts ->
                            NpcFamilies.standsAside(intent, facts)?.let { return@Condition it }
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                intent.boundsByLegs && !facts.sidearm -> Verdict.Rejected("advances_by_legs")
                                failure?.anchor == intent.anchor -> Verdict.Rejected("preferred_query_exhausted")
                                else -> Verdict.Allowed
                            }
                        }
                    }, decompose = { intent, _ -> TaskNetwork.sequence(POST.call(intent)) }),
                    // A search failed and he stands on a squadmate's block: before the next search
                    // he steps off it, searching round where he stands with no lane asked for. The
                    // neighbour and closer searches fail in place, so he stood touching through the
                    // whole round, and the last resort then held him there (#299).
                    Method("spread_post", eligible = { intent ->
                        Condition { facts ->
                            NpcFamilies.standsAside(intent, facts)?.let { return@Condition it }
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            if (failure?.anchor != intent.anchor) Verdict.Rejected("no_failed_search") else spreads(intent, failure, facts)
                        }
                    }, decompose = { intent, _ ->
                        TaskNetwork.sequence(POST.call(intent.copy(duty = CombatDuty.REAR_GUARD, anchor = intent.origin, hold = false, holdFeet = false, requireCover = false, requireShelter = false, legs = false, coveredBy = emptySet(), spread = true)))
                    }),
                    Method("neighbor_post", eligible = { intent ->
                        Condition { facts ->
                            NpcFamilies.standsAside(intent, facts)?.let { return@Condition it }
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                failure?.anchor != intent.anchor || failure.attempts > NEIGHBOR_ATTEMPTS -> Verdict.Rejected("local_alternatives_exhausted")
                                spreads(intent, failure, facts) == Verdict.Allowed -> Verdict.Rejected("spread_due")
                                else -> Verdict.Allowed
                            }
                        }
                    }, decompose = { intent, facts ->
                        val attempt = facts.value(TacticalFacts.POST_FAILURE)!!.attempts
                        val dx = (intent.focus?.x ?: intent.anchor.x) - intent.origin.x
                        val dz = (intent.focus?.z ?: intent.anchor.z + 1) - intent.origin.z
                        val length = kotlin.math.hypot(dx, dz).coerceAtLeast(0.01)
                        val offset = listOf(12.0 to 0.0, -12.0 to 0.0, 0.0 to 12.0, 0.0 to -12.0)[attempt - 1]
                        val anchor = Point3(
                            intent.anchor.x - dz / length * offset.first + dx / length * offset.second,
                            intent.anchor.y,
                            intent.anchor.z + dx / length * offset.first + dz / length * offset.second,
                        )
                        TaskNetwork.sequence(POST.call(intent.copy(anchor = anchor, legs = false, coveredBy = emptySet())))
                    }),
                    // Terrain hides the enemy from the whole neighbourhood of the anchor: look for a
                    // line of fire closer to the enemy or along the front instead of retrying the
                    // same blind spot forever.
                    Method("closer_post", eligible = { intent ->
                        Condition { facts ->
                            NpcFamilies.standsAside(intent, facts)?.let { return@Condition it }
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                failure?.anchor != intent.anchor || failure.attempts <= NEIGHBOR_ATTEMPTS -> Verdict.Rejected("neighbors_not_exhausted")
                                facts.sidearm -> Verdict.Rejected("sidearm")
                                intent.duty.holdsBack -> Verdict.Rejected("holds_back")
                                spreads(intent, failure, facts) == Verdict.Allowed -> Verdict.Rejected("spread_due")
                                else -> closer(intent, failure)
                            }
                        }
                    }, decompose = { intent, facts ->
                        val step = facts.value(TacticalFacts.POST_FAILURE)!!.attempts - NEIGHBOR_ATTEMPTS - 1
                        TaskNetwork.sequence(POST.call(intent.copy(anchor = closerAnchors(intent)[step], legs = false, coveredBy = emptySet())))
                    }),
                    // Every search round the anchor failed: his order and gun move him until the
                    // failure is forgotten and the search starts again. Without it the man had no
                    // plan at all and stood with his last failed search for a task.
                    Method("hold_as_ordered", eligible = { intent ->
                        Condition { facts ->
                            NpcFamilies.standsAside(intent, facts)?.let { return@Condition it }
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                failure?.anchor != intent.anchor || failure.attempts <= NEIGHBOR_ATTEMPTS -> Verdict.Rejected("posts_not_exhausted")
                                !facts.sidearm && !intent.duty.holdsBack && closer(intent, failure) == Verdict.Allowed -> Verdict.Rejected("closer_post_left")
                                spreads(intent, failure, facts) == Verdict.Allowed -> Verdict.Rejected("spread_due")
                                else -> Verdict.Allowed
                            }
                        }
                    }, decompose = { intent, facts -> engageOrHold(intent, facts, forward = !intent.duty.holdsBack, POST.call(intent.copy(hold = true, holdFeet = intent.duty.commandsMovement, requireCover = false, requireShelter = false, legs = false, coveredBy = emptySet()))) }),
                ),
            ),
            CompoundTask(
                UNDER_FIRE,
                listOf(
                    Method("take_cover", eligible = { _ -> UnderFire.coverLeft() }, decompose = { intent, facts ->
                        val sector = facts.value(TacticalFacts.FIRE_SECTOR)!!
                        TaskNetwork.sequence(COVER_MOVE_TASK.call(CoverMove(intent.actor, sector, fallBack = false, armour = facts.value(ArmourFacts.RELEVANT))), COVER_CYCLE_TASK.call(CoverCycle(intent.actor, facts.value(TacticalFacts.COVER_CYCLES) ?: 0, facts.value(ArmourFacts.RELEVANT))))
                    }),
                    Method("dig_in", eligible = { _ -> UnderFire.noCover(dig = true) }, decompose = { intent, facts -> TaskNetwork.sequence(DIG_TASK.call(DigIn(intent.actor, facts.value(TacticalFacts.DIGS) ?: 0))) }),
                    // Engaged with no cover in reach: his post where he stands, his gun fighting from it.
                    // In the open under fire he never moves towards the enemy (forward = false).
                    Method("hold_open", eligible = { _ -> Condition.all(UnderFire.noCover(dig = false), Condition.test(TacticalFacts.ENGAGED, "not_engaged") { it }) }, decompose = { intent, facts -> engageOrHold(intent, facts, forward = false, POST.call(intent.copy(hold = true, requireCover = false, requireShelter = false, legs = false, coveredBy = emptySet()))) }),
                    Method("fall_back", eligible = { _ -> Condition.all(UnderFire.noCover(dig = false), Condition.test(TacticalFacts.ENGAGED, "engaged") { !it }) }, decompose = { intent, facts ->
                        val sector = facts.value(TacticalFacts.FIRE_SECTOR)!!
                        TaskNetwork.sequence(COVER_MOVE_TASK.call(CoverMove(intent.actor, sector, fallBack = true, armour = facts.value(ArmourFacts.RELEVANT))), COVER_CYCLE_TASK.call(CoverCycle(intent.actor, facts.value(TacticalFacts.COVER_CYCLES) ?: 0, facts.value(ArmourFacts.RELEVANT))))
                    }),
                ),
            ),
            CompoundTask(
                IN_HOLE,
                listOf(
                    Method("leave_hole", eligible = { _ -> UnderFire.leaveHole() }, decompose = { intent, _ -> TaskNetwork.sequence(LEAVE_HOLE_TASK.call(LeaveHole(intent.actor))) }),
                    Method("dig_deeper", eligible = { _ -> UnderFire.digDeeper() }, decompose = { intent, facts -> TaskNetwork.sequence(DIG_TASK.call(DigIn(intent.actor, facts.value(TacticalFacts.DIGS)!!))) }),
                    Method("hold_in_hole", eligible = { _ -> UnderFire.holdHole() }, decompose = { intent, facts -> TaskNetwork.sequence(HOLD_HOLE_TASK.call(HoldHole(intent.actor, facts.value(TacticalFacts.DIGS)!!))) }),
                ),
            ),
            CompoundTask(
                FIGHT_TASK,
                listOf(
                    // An engage-only man has no cover or hole of his own in his plan to answer first.
                    Method("grenade", eligible = { _ -> Condition { facts -> Grenades.throws(facts, Verdict.Rejected("no_under_fire_plan")) } }, decompose = { task, facts -> TaskNetwork.sequence(Grenades.next(task.actor, facts)) }),
                    Method("engage", eligible = { _ -> Condition.ALWAYS }, decompose = { task, _ -> TaskNetwork.sequence(ENGAGE_TASK.call(task)) }),
                ),
            ),
            Grenades.task(),
            CompoundTask(
                ENGAGE_TASK,
                Engage.ORDER.map { move ->
                    Method(Engage.methodId(move), eligible = { task -> Engage.applies(move, task) }, decompose = { task, facts ->
                        TaskNetwork.sequence(ENGAGE_UNIT.call(EngageUnit(task.actor, move, facts.value(TacticalFacts.ENGAGE_UNITS) ?: 0, facts.sidearm, facts.value(ArmourFacts.RELEVANT))))
                    })
                },
            ),
            PrimitiveTask(ENGAGE_UNIT) { unit ->
                PrimitiveSpec(
                    Engage.identity(unit),
                    ActionSpec(ENGAGE, unit, setOf(ResourceKey(unit.actor, "motion")), timeoutTicks = Engage.UNIT_TIMEOUT),
                    TaskContract(maintain = Engage.maintain(), completed = Engage.unitOver(unit)),
                    planning = ownActor(unit.actor),
                )
            },
            PrimitiveTask(COVER_MOVE_TASK) { move -> motion("cover_move", COVER_MOVE, move, move.actor, Condition.test(TacticalFacts.SHELTERED, "not_sheltered") { it }) },
            // Done once its dwell and peek are over; the count it raises makes the plan look again.
            PrimitiveTask(COVER_CYCLE_TASK) { cycle -> motion("cover_cycle:${cycle.number}", COVER_CYCLE, cycle, cycle.actor, Condition.test(TacticalFacts.COVER_CYCLES, "cycle_running") { it > cycle.number }) },
            PrimitiveTask(DIG_TASK) { dig -> motion("dig:${dig.number}", DIG, dig, dig.actor, Condition.test(TacticalFacts.DIGS, "digging") { it > dig.number }) },
            PrimitiveTask(HOLD_HOLE_TASK) { hold -> motion("hold_hole:${hold.digs}", HOLD_HOLE, hold, hold.actor, UnderFire.holdOver()) },
            PrimitiveTask(LEAVE_HOLE_TASK) { leave -> motion("leave_hole", LEAVE_HOLE, leave, leave.actor, Condition.test(TacticalFacts.DIGS, "in_hole") { it == 0 }) },
            CompoundTask(POST, listOf(Method<PositionIntent>("constrained_post", decompose = { intent, facts -> TaskNetwork.sequence(POSITION_UNIT.call(intent.copy(armour = facts.value(ArmourFacts.RELEVANT)))) }))),
            PrimitiveTask(POSITION_UNIT) { intent -> position("position", intent, Condition.test(TacticalFacts.ARRIVED, "post_not_reached") { it }) },
            // Each leg has its own identity: a leg ended on its post is done, and the next one is a
            // new search from there, not the same execution carried on.
            CompoundTask(LEG, listOf(Method<MemberLeg>("constrained_leg", decompose = { leg, facts -> TaskNetwork.sequence(LEG_UNIT.call(leg.copy(intent = leg.intent.copy(armour = facts.value(ArmourFacts.RELEVANT))))) }))),
            PrimitiveTask(LEG_UNIT) { leg -> position("leg:${leg.number}", leg.intent, Condition.test(TacticalFacts.AT_POST, "leg_post_not_reached") { it }) },
            PrimitiveTask(PAUSE) { pause ->
                PrimitiveSpec(
                    "fire:${pause.leg}",
                    ActionSpec(FIRE_PAUSE, pause, setOf(ResourceKey(pause.actor, "motion")), timeoutTicks = FIRE_PAUSE_TIMEOUT),
                    TaskContract(maintain = Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it }),
                    planning = ownActor(pause.actor),
                )
            },
        ) + ArmourResponse.tasks() + crewTasks.second + airTasks.second + tankTasks.second + DroneStrike.tasks { call -> TaskNetwork.sequence(ENGAGE_TASK.call(EngageTask(call.actor))) },
    )
    private const val NEIGHBOR_ATTEMPTS = 4

    private val FactView.sidearm get() = value(TacticalFacts.SIDEARM) == true

    /** A fire pause that neither fired nor ended by itself within 10 s is given up. */
    private const val FIRE_PAUSE_TIMEOUT = 200L

    private fun position(identity: String, intent: PositionIntent, completed: Condition): PrimitiveSpec {
        val maintain = Condition.all(
            Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it },
            if (intent.requireCover) Condition.test(TacticalFacts.COVER, "cover_lost") { it } else Condition.ALWAYS,
        )
        return PrimitiveSpec(
            identity,
            ActionSpec(
                POSITION,
                intent,
                if (intent.commandsMovement) setOf(ResourceKey(intent.actor, "motion")) else emptySet(),
                // A hold gives up like a search: should its failure never be forgotten, it ends anyway.
                timeoutTicks = if (intent.duty.commandsMovement) 1200 else Long.MAX_VALUE,
            ),
            // In the start, not the maintain: covering fire that lapses mid-rush must not stop him in the open.
            TaskContract(start = if (intent.coveredBy.isNotEmpty()) Condition.test(TacticalFacts.SUPPORT_FIRING, "awaiting_support") { it } else Condition.ALWAYS, maintain = maintain, completed = if (intent.holdFeet) Condition { Verdict.Rejected("holding_position") } else completed),
            planning = ownActor(intent.actor),
        )
    }

    /** A cover or hole action of [actor]'s own: it holds his motion while it lasts and must not outlive his ability to move. */
    private fun <P : Any> motion(identity: String, key: ActionKey<P>, parameters: P, actor: AgentId, completed: Condition) =
        PrimitiveSpec(
            identity,
            ActionSpec(key, parameters, setOf(ResourceKey(actor, "motion")), timeoutTicks = COVER_TIMEOUT),
            TaskContract(maintain = Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it }, completed = completed),
            planning = ownActor(actor),
        )

    /** A cover action that has neither finished nor failed in a minute is given up and replanned. */
    private const val COVER_TIMEOUT = 1200L

    private fun ownActor(actor: AgentId) = Condition { facts -> if (facts.owner == actor) Verdict.Allowed else Verdict.Rejected("foreign_actor_intent") }

    /** Steps, forward towards the enemy and sideways along the front, tried once the neighbours fail. */
    private val CLOSER_STEPS = listOf(12.0 to 0.0, 24.0 to 0.0, 12.0 to 16.0, 12.0 to -16.0, 24.0 to 16.0, 24.0 to -16.0, 36.0 to 0.0)

    /** Whether [closerAnchors] still has a step to try after [failure], the neighbours being exhausted. */
    private fun closer(intent: PositionIntent, failure: LocalPositionFailure): Verdict {
        val step = failure.attempts - NEIGHBOR_ATTEMPTS - 1
        return when {
            !blind(failure) -> Verdict.Rejected("not_blind")
            step >= closerAnchors(intent).size -> Verdict.Rejected("closer_alternatives_exhausted")
            else -> Verdict.Allowed
        }
    }

    /** Most of the last query's candidates had no line of fire to the enemy. */
    private fun blind(failure: LocalPositionFailure) = (failure.rejections["blocked_shot"] ?: 0) * 2 >= max(1, failure.rejections.values.sum())

    /**
     * Anchors closer to [PositionIntent.focus] than [PositionIntent.anchor], for a firing or
     * moving member; none nearer the focus than its [PositionIntent.standoff].
     */
    fun closerAnchors(intent: PositionIntent): List<Point3> {
        val focus = intent.focus ?: return emptyList()
        if (intent.duty != CombatDuty.FIRE && intent.duty != CombatDuty.MOVE) return emptyList()
        val dx = focus.x - intent.anchor.x
        val dz = focus.z - intent.anchor.z
        val length = kotlin.math.hypot(dx, dz)
        if (length < 1.0) return emptyList()
        val fx = dx / length
        val fz = dz / length
        return CLOSER_STEPS.map { (forward, side) -> Point3(intent.anchor.x + fx * forward - fz * side, intent.anchor.y, intent.anchor.z + fz * forward + fx * side) }
            .filter { kotlin.math.hypot(it.x - focus.x, it.z - focus.z) >= max(intent.standoff, MIN_STANDOFF) }
    }

    /** Never search right on top of the enemy, whatever the role allows. */
    private const val MIN_STANDOFF = 8.0

    fun at(level: AgentLevel) =
        when (level) {
            AgentLevel.OPERATION -> operation
            AgentLevel.SQUAD -> squad
            AgentLevel.TEAM -> team
            AgentLevel.NPC -> npc
        }
}

/**
 * One squad's fight as [SquadManners] weighs it.
 *
 * @property higherEnemy the focus stood more than 4 blocks above the squad within the last 10 s.
 * @property reinforced the squad has a barracks that replaces its fallen, so its losses never
 *   make it withdraw (#57).
 * @property movers how many of its men advance by legs ([PositionIntent.legs]) before any
 *   strategy reshapes their tasks.
 * @property flanking some of its men are on the arc round a flank.
 * @property scattered a third of its men and at least two, aside from flankers and men who watch
 *   or search away from it on purpose, are farther from it than twice its perimeter radius.
 * @property gathered all those men are within its perimeter radius.
 * @property regrouping it holds [CombatStrategy.REGROUP], and has for less than 20 s.
 * @property regroupSpent it has regrouped for 20 s, or stopped regrouping less than 30 s ago:
 *   it does not regroup now, scattered or not.
 * @property underFire one of its men was hit within the last 13 s or so: [incoming] lasts 3 s
 *   after a hit and this 10 s more. A few distant riflemen hit too seldom to keep [incoming], and a
 *   strategy weighed by it was taken up and dropped every few seconds.
 * @property retreating it falls back to its rally point on a RETREAT order (#253). Its [contact]
 *   is then only an enemy its men see near, see [com.sbwnpc.squad.combat.tactics.TacticalSnapshot.closeContact].
 * @property assessment the inputs the strategies' considerations weigh beyond these flags.
 * @property coverStalled within the last 30 s one of its men waited 5 s for covering fire for a
 *   rush that never came: it does not bound by halves, and its teams do not rush in pairs. A rush
 *   that started without the fire anyway crossed open ground uncovered (in-game 03.11). Once the
 *   30 s are up it may bound again, and stall again if its halves still have nothing to fire at.
 */
data class CombatSituation(val contact: Boolean, val offensive: Boolean, val higherEnemy: Boolean, val incoming: Boolean, val readyFraction: Double, val lossFraction: Double, val continuation: Boolean, val reinforced: Boolean = false, val movers: Int = 0, val movingTeams: Int = 0, val flanking: Boolean = false, val scattered: Boolean = false, val gathered: Boolean = false, val regrouping: Boolean = false, val regroupSpent: Boolean = false, val underFire: Boolean = incoming, val retreating: Boolean = false, val coverStalled: Boolean = false, val assessment: SquadAssessment = SquadAssessment())

/**
 * What a squad's strategies and approaches weigh besides the bare [CombatSituation] flags,
 * collected once per snapshot. Null is unknown: the consideration then has no effect.
 *
 * Every value is rounded to a step ([of]): the situation is a planner fact, and one that changed
 * on every refresh made the squad replan on every refresh.
 *
 * @property forceRatio the squad's fighting men to the enemies it sees, an armoured one counting
 *   for several; null with no enemy in sight.
 * @property ammo the share of its men still on their main weapon; null out of contact.
 * @property health its men's mean health, 0 to 1; null out of contact.
 * @property objectiveDistance blocks from its centre to its attack's objective; null without one.
 * @property fireSupport 1 when a mortar crew or a tank fights with its attack, else 0.
 * @property light the brightness at its centre, 0 to 1, in an attack.
 * @property sinceContact seconds since it last saw an enemy; null if it never has.
 */
data class SquadAssessment(
    val forceRatio: Double? = null,
    val ammo: Double? = null,
    val health: Double? = null,
    val objectiveDistance: Double? = null,
    val fireSupport: Double? = null,
    val light: Double? = null,
    val sinceContact: Double? = null,
) {
    companion object {
        /** [value] rounded to the nearest multiple of [step]. */
        fun of(value: Double, step: Double) = round(value / step) * step
    }
}

/**
 * How a squad's strategy methods choose between the [CombatStrategy] entries.
 *
 * A strategy applies when its rules allow the [TacticalFacts.SITUATION] and scores what its rules
 * say. The strategy the squad holds ([TacticalFacts.MANNER]) is worth [ADVANTAGE] more, and while
 * it is locked ([TacticalFacts.MANNER_LOCK], [MINIMUM_TICKS] after it was chosen) and still
 * allowed nothing else applies. Without that a squad whose scores hovered round a tie swapped
 * strategy, and every member's task with it, on every refresh.
 */
object SquadManners {
    /** How long a newly chosen strategy is kept while still allowed. */
    const val MINIMUM_TICKS = 40L

    /**
     * How much more the strategy held is worth. A held score is clamped to 1, so a strategy able
     * to score 1 would start losing exact ties by method id.
     */
    const val ADVANTAGE = 0.12

    /** The squad method that carries out [strategy]. */
    fun methodId(strategy: CombatStrategy) = strategy.name.lowercase()

    /** The strategy whose method is [id], or null for any other method. */
    fun of(id: String) = CombatStrategy.entries.firstOrNull { methodId(it) == id }

    /**
     * Whether [strategy]'s method applies: its rules allow the situation, and no other strategy is
     * held under lock while still allowed (rejected as `incumbent_minimum`).
     */
    fun eligible(strategy: CombatStrategy) =
        Condition { facts ->
            val situation = facts.value(TacticalFacts.SITUATION) ?: return@Condition Verdict.Unknown(setOf(TacticalFacts.SITUATION.name))
            val held = facts.value(TacticalFacts.MANNER)
            when {
                !strategy.rules.allowed(situation) -> Verdict.Rejected(strategy.rules.reason)
                held == null || held == strategy || facts.value(TacticalFacts.MANNER_LOCK) != true || !held.rules.allowed(situation) -> Verdict.Allowed
                else -> Verdict.Rejected("incumbent_minimum")
            }
        }

    /** [strategy]'s score in the observed situation, with [ADVANTAGE] when the squad holds it. */
    fun score(strategy: CombatStrategy, facts: FactView): UtilityScore {
        val situation = facts.value(TacticalFacts.SITUATION) ?: return UtilityScore.NEUTRAL
        val score = strategy.rules.score(situation)
        val held = facts.value(TacticalFacts.MANNER) == strategy
        // A vetoed strategy stays vetoed held: the advantage only keeps one still worth something.
        return if (held && score.value > 0.0) score.copy(value = min(1.0, score.value + ADVANTAGE), factors = score.factors + UtilityFactor("held", ADVANTAGE)) else score
    }
}

/**
 * How a squad's approach methods choose between [SquadApproach.FLANK] and [SquadApproach.FRONTAL].
 *
 * A flank needs [TacticalFacts.FLANK_OPEN]. It is worth more the more of the squad is ready to fire
 * and less the more it has lost; the frontal approach is worth a little less than a flank by a
 * squad that is neither ready nor hurt, so a squad that can flank does, as before this choice. The
 * approach held is worth [SquadManners.ADVANTAGE] more.
 */
object SquadApproaches {
    /** The approach method that takes [approach]. */
    fun methodId(approach: SquadApproach) = approach.name.lowercase()

    /** The approach whose method is [id], or null for any other method. */
    fun of(id: String) = SquadApproach.entries.firstOrNull { methodId(it) == id }

    /** Whether [approach]'s method applies: a flank only while [TacticalFacts.FLANK_OPEN]. */
    fun eligible(approach: SquadApproach) =
        when (approach) {
            SquadApproach.FLANK -> Condition.test(TacticalFacts.FLANK_OPEN, "flank_closed") { it }
            SquadApproach.FRONTAL -> Condition.ALWAYS
        }

    /** [approach]'s score in the observed situation, with the advantage when the squad holds it. */
    fun score(approach: SquadApproach, facts: FactView): UtilityScore {
        val situation = facts.value(TacticalFacts.SITUATION)
        val a = situation?.assessment ?: SquadAssessment()
        val score = when (approach) {
            SquadApproach.FLANK -> UtilityScore.product(
                (FLANK_BASE + (situation?.readyFraction ?: 0.0) * FLANK_READINESS - ((situation?.lossFraction ?: 0.0) - FLANK_LOSS_ALLOWANCE).coerceAtLeast(0.0) * FLANK_LOSSES).coerceIn(0.0, 1.0),
                listOf(
                    // Someone has to hold the enemy's eyes on the front: alone, a squad gets little from a flank.
                    Consideration("force", a.forceRatio, ResponseCurve.floored(OUTNUMBERED_FLOOR, OUTNUMBERED, EVEN)),
                    Consideration("fire_support", a.fireSupport, ResponseCurve.floored(UNSUPPORTED_FLOOR, 0.0, 1.0)),
                    // The way round costs time that grows with the distance to the point.
                    Consideration("objective_distance", a.objectiveDistance, ResponseCurve.floored(FAR_FLOOR, FAR, NEAR)),
                    // In the dark the enemy sees a flank coming later.
                    Consideration("light", a.light, ResponseCurve.floored(DAYLIGHT_FLOOR, 1.0, DARK)),
                ),
                "flank",
            )

            SquadApproach.FRONTAL -> UtilityScore.product(FRONTAL, emptyList(), "frontal")
        }
        val held = facts.value(TacticalFacts.APPROACH) == approach
        return if (held && score.value > 0.0) score.copy(value = min(1.0, score.value + SquadManners.ADVANTAGE), factors = score.factors + UtilityFactor("held", SquadManners.ADVANTAGE)) else score
    }

    private const val FLANK_BASE = 0.5
    private const val FLANK_READINESS = 0.3

    // The approach is mostly chosen before contact, with nobody ready yet: losses up to 30% leave
    // the flank as it was, a third or more makes the squad come on frontally.
    private const val FLANK_LOSS_ALLOWANCE = 0.3
    private const val FLANK_LOSSES = 2.0
    private const val FRONTAL = 0.45
    private const val OUTNUMBERED_FLOOR = 0.6
    private const val OUTNUMBERED = 0.7
    private const val EVEN = 1.3
    private const val UNSUPPORTED_FLOOR = 0.85
    private const val FAR_FLOOR = 0.4
    private const val FAR = 160.0
    private const val NEAR = 48.0
    private const val DAYLIGHT_FLOOR = 0.9
    private const val DARK = 0.3
}

/** How a team's men rush when they advance by legs ([PositionIntent.legs]). */
enum class TeamRush {
    /** Each man rushes when his own leg and fire are done. */
    TOGETHER,

    /** The men pair off, and each rush waits for the partner's fire ([PositionIntent.coveredBy]). */
    PAIRS,
}

/**
 * How a team's methods choose between [TeamRush.TOGETHER] and [TeamRush.PAIRS], from the team's own
 * mission alone.
 *
 * Pairs cost time, so they are worth it under fire and over long legs. Movers someone else covers
 * already, the halves of a squad bounding by [CombatStrategy.BOUND_OVERWATCH], are left as they are.
 */
object TeamRushes {
    private fun movers(mission: TeamMission) = mission.members.filter { it.legs && it.duty == CombatDuty.MOVE && !it.assault && !it.ordered && it.coveredBy.isEmpty() }

    /** Whether [rush]'s method applies: pairs need two movers nobody covers yet, in a squad whose cover has not stalled. */
    fun eligible(rush: TeamRush, mission: TeamMission) =
        when (rush) {
            TeamRush.TOGETHER -> Condition.ALWAYS
            TeamRush.PAIRS -> if (movers(mission).size >= 2) Condition.test(TacticalFacts.COVER_STALLED, "cover_stalled") { !it } else Condition { Verdict.Rejected("no_pair") }
        }

    /** [rush]'s score: pairs by whether the movers are under fire and by how far they have to go. */
    fun score(rush: TeamRush, mission: TeamMission): UtilityScore =
        when (rush) {
            TeamRush.TOGETHER -> UtilityScore(TOGETHER, reason = "together")

            TeamRush.PAIRS -> {
                val movers = movers(mission)
                // Cover is required of a mover exactly when his squad is under fire, see PostRequirements.
                val underFire = movers.count { it.requireCover }.toDouble() / max(1, movers.size)
                val leg = movers.map { hypot(it.anchor.x - it.origin.x, it.anchor.z - it.origin.z) }.average()
                UtilityScore.weighted(
                    listOf(UtilityFactor("base", PAIRS_BASE), UtilityFactor("under_fire", underFire, UNDER_FIRE_WEIGHT), UtilityFactor("distance", min(1.0, leg / LONG_LEG), LEG_WEIGHT)),
                    "pairs",
                )
            }
        }

    /**
     * [mission] carried out by [rush]. In pairs, the movers in order along their ids pair off and
     * each covers the other; an odd one out goes with the last pair, covered by both of them.
     * Neither needs covering fire for the whole rush any more ([PositionIntent.requireCover]).
     */
    fun shape(rush: TeamRush, mission: TeamMission): TeamMission {
        if (rush == TeamRush.TOGETHER) return mission
        val movers = movers(mission).map { it.actor.id }.sortedBy { it.toString() }
        val pairs = movers.chunked(2).let { chunks -> if (chunks.last().size == 1 && chunks.size > 1) chunks.dropLast(2) + listOf(chunks[chunks.size - 2] + chunks.last()) else chunks }
        val partners = pairs.flatMap { pair -> pair.map { man -> man to (pair.toSet() - man) } }.toMap()
        return mission.copy(members = mission.members.map { intent -> partners[intent.actor.id]?.let { intent.copy(requireCover = false, coveredBy = it) } ?: intent })
    }

    // Under fire pairs win once the movers have 12 blocks or more to go (0.2 + 1 + 0.5 per
    // 32 blocks, weighed over 2.5); never without fire.
    private const val TOGETHER = 0.55
    private const val PAIRS_BASE = 0.2
    private const val UNDER_FIRE_WEIGHT = 1.0
    private const val LEG_WEIGHT = 0.5

    /** A leg this long or longer counts as long. */
    private const val LONG_LEG = 32.0
}

/**
 * Whether a man whose search failed steps off a squadmate's block (spread_post) before any other
 * post method. A step that ends still crowded is taken again: only a failed one is remembered,
 * so the steps last at most until the failure is forgotten.
 */
private fun spreads(intent: PositionIntent, failure: LocalPositionFailure, facts: FactView): Verdict =
    when {
        failure.spreadTried -> Verdict.Rejected("spread_failed")
        !intent.duty.commandsMovement -> Verdict.Rejected("feet_not_his")
        facts.value(TacticalFacts.UNDER_FIRE) == true -> Verdict.Rejected("under_fire")
        facts.value(TacticalFacts.CROWDED) != true -> Verdict.Rejected("not_crowded")
        else -> Verdict.Allowed
    }
