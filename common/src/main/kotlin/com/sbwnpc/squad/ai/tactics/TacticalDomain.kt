package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationAction
import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
 * @property coveredBy the men whose fire covers his rushes: each rush waits to start until one
 *   of them fires ([TacticalFacts.SUPPORT_FIRING]). Empty, he rushes when ready.
 */
data class PositionIntent(val actor: AgentId, val duty: CombatDuty, val origin: Point3, val anchor: Point3, val focus: Point3?, val requireCover: Boolean = false, val requireShelter: Boolean = false, val minimumHeight: Double? = null, val exposureWeight: Double = 1.0, val inspection: Point3? = null, val elevationWeight: Double = 0.0, val assault: Boolean = false, val standoff: Double = 0.0, val ordered: Boolean = false, val hold: Boolean = false, val legs: Boolean = false, val coveredBy: Set<java.util.UUID> = emptySet()) {
    init {
        require(actor.level == AgentLevel.NPC && exposureWeight.isFinite() && exposureWeight >= 0 && elevationWeight.isFinite() && elevationWeight >= 0)
    }

    /** Whether this task takes over his movement, see [CombatDuty.commandsMovement] and [hold]. */
    val commandsMovement get() = duty.commandsMovement && !hold

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
 */
data class SquadShaping(val turn: BoundTurn? = null, val rally: Point3? = null)
data class TeamMission(val agent: AgentId, val members: List<PositionIntent>)
data class SquadMission(val agent: AgentId, val teams: List<TeamMission>)

/** A squad's [mission] under the [strategy] its method chose, for the choice of its [SquadApproach]. */
data class StrategyMission(val mission: SquadMission, val strategy: CombatStrategy)

/** How a squad attacking alone comes at its objective. */
enum class SquadApproach {
    /** A manoeuvre group goes round to a flank along the arc while the rest fixes the enemy with fire. */
    FLANK,

    /** The whole squad comes on from where it is. */
    FRONTAL,
}
data class AreaControlTask(val operation: java.util.UUID, val objective: Point3, val commands: Map<java.util.UUID, java.util.UUID>)
data class OperationMission(val squads: List<SquadMission>, val secure: AreaControlTask? = null, val attack: AttackRoles = AttackRoles.NONE)

/** Typed observations are scoped by FactSnapshot.owner; no world queries live in this domain. */
data class LocalPositionFailure(val anchor: Point3, val failedAt: Long, val attempts: Int, val rejections: Map<String, Int>)

object TacticalFacts {
    val POST_FAILURE = FactKey("post.local_failure", LocalPositionFailure::class.java)
    val AREA_SECURED = FactKey("operation.area_secured", Boolean::class.javaObjectType)
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

    /** Where the squad gathers while it regroups under [CombatStrategy.REGROUP]. */
    val RALLY = FactKey("squad.rally", Point3::class.java)

    /** The half covering his rush is firing, or has had long enough to start; see [PositionIntent.coveredBy]. */
    val SUPPORT_FIRING = FactKey("actor.support_firing", Boolean::class.javaObjectType)
    val ATTACK_PHASE = FactKey("operation.attack_phase", AttackPhase::class.java)
}

object TacticalDomain {
    val OPERATION = TaskKey("operation.execute", OperationMission::class.java)
    val SQUAD = TaskKey("squad.execute", SquadMission::class.java)
    val TEAM = TaskKey("team.execute", TeamMission::class.java)
    val NPC = TaskKey("npc.execute", PositionIntent::class.java)
    val SECURE = ActionKey("operation.secure_area", AreaControlTask::class.java)
    private val SECURE_TASK = TaskKey("operation.hold_and_inspect", AreaControlTask::class.java)
    val POSITION = ActionKey("npc.position", PositionIntent::class.java)

    /** Fire from the post a leg ended on, until he has fired or found nothing to fire at. */
    val FIRE_PAUSE = ActionKey("npc.fire_pause", FirePause::class.java)
    private val DELEGATE = TaskKey("delegate.task", DelegationTask::class.java)
    private val APPROACH = TaskKey("squad.approach", StrategyMission::class.java)

    private fun delegate() =
        PrimitiveTask(DELEGATE) { task ->
            PrimitiveSpec(task.child.id.toString(), ActionSpec(DelegationAction.KEY, task.copy(continuous = true), setOf(ResourceKey(task.child, "command")), timeoutTicks = Long.MAX_VALUE), TaskContract())
        }
    private fun children(tasks: List<DelegationTask>) = TaskNetwork(tasks.map { NetworkNode(it.child.id.toString(), DELEGATE.call(it)) })
    private fun coordinate(mission: OperationMission, roles: AttackRoles): TaskNetwork {
        val nodes = mission.squads.map { squad -> NetworkNode(squad.agent.id.toString(), DELEGATE.call(DelegationTask(squad.agent, SQUAD.call(OperationTactics.apply(squad, roles))))) }
        return TaskNetwork(nodes + listOfNotNull(mission.secure?.let { NetworkNode("secure", SECURE_TASK.call(it)) }))
    }

    val operation = HtnDomain(
        AgentLevel.OPERATION,
        listOf(
            CompoundTask(
                OPERATION,
                // Without an attack phase the squads carry out their own missions; an attacking
                // operation deploys a firing line, assaults into its objective or clears around it.
                listOf(
                    Method<OperationMission>("coordinate", eligible = {
                        Condition { facts -> if (facts.value(TacticalFacts.ATTACK_PHASE) == null) Verdict.Allowed else Verdict.Rejected("attack_phase_known") }
                    }, decompose = { mission, _ -> coordinate(mission, AttackRoles.NONE) }),
                ) + AttackPhase.entries.map { phase ->
                    Method<OperationMission>(phase.name.lowercase(), eligible = {
                        Condition.test(TacticalFacts.ATTACK_PHASE, "other_attack_phase") { it == phase }
                    }, decompose = { mission, _ -> coordinate(mission, mission.attack) })
                },
            ),
            delegate(),
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
                CombatStrategy.entries.map { strategy ->
                    Method(
                        SquadManners.methodId(strategy),
                        eligible = { SquadManners.eligible(strategy) },
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
                            val shaping = SquadShaping(facts.value(TacticalFacts.BOUND_TURN), facts.value(TacticalFacts.RALLY))
                            val tasks = mission.teams.map { team ->
                                val selected = team.copy(
                                    // A member assaulting for the operation keeps its role whatever the squad's own strategy,
                                    // and so does one moving for his order itself: HOLD turned a following squad's bounds
                                    // back into fire from where it stood (#116).
                                    members = team.members.map { intent -> if (intent.assault || intent.ordered) intent else strategy.rules.shape(intent, shaping) },
                                )
                                DelegationTask(team.agent, TEAM.call(selected))
                            }
                            children(tasks)
                        },
                    )
                },
            ),
            delegate(),
        ),
    )
    val team = HtnDomain(
        AgentLevel.TEAM,
        listOf(
            CompoundTask(
                TEAM,
                TeamRush.entries.map { rush ->
                    Method(
                        rush.name.lowercase(),
                        eligible = { mission -> TeamRushes.eligible(rush, mission) },
                        score = { mission, _ -> TeamRushes.score(rush, mission) },
                        decompose = { mission, _ -> children(TeamRushes.shape(rush, mission).members.map { DelegationTask(it.actor, NPC.call(it)) }) },
                    )
                },
            ),
            delegate(),
        ),
    )
    private val POST = TaskKey("npc.post", PositionIntent::class.java)
    private val LEG = TaskKey("npc.leg", MemberLeg::class.java)
    private val PAUSE = TaskKey("npc.fire_pause", FirePause::class.java)
    val npc = HtnDomain(
        AgentLevel.NPC,
        listOf(
            CompoundTask(
                NPC,
                listOf(
                    // A mover in a fight rushes from cover to cover and fires from each, rather than
                    // running the whole way to his slot under fire.
                    Method("fire_and_bound", eligible = { intent ->
                        Condition { facts ->
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                !intent.boundsByLegs -> Verdict.Rejected("not_advancing_by_legs")
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
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                intent.boundsByLegs -> Verdict.Rejected("advances_by_legs")
                                failure?.anchor == intent.anchor -> Verdict.Rejected("preferred_query_exhausted")
                                else -> Verdict.Allowed
                            }
                        }
                    }, decompose = { intent, _ -> TaskNetwork.sequence(POST.call(intent)) }),
                    Method("neighbor_post", eligible = { intent ->
                        Condition { facts ->
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            if (failure?.anchor == intent.anchor && failure.attempts <= NEIGHBOR_ATTEMPTS) Verdict.Allowed else Verdict.Rejected("local_alternatives_exhausted")
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
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            if (failure?.anchor != intent.anchor || failure.attempts <= NEIGHBOR_ATTEMPTS) Verdict.Rejected("neighbors_not_exhausted") else closer(intent, failure)
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
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            when {
                                failure?.anchor != intent.anchor || failure.attempts <= NEIGHBOR_ATTEMPTS -> Verdict.Rejected("posts_not_exhausted")
                                closer(intent, failure) == Verdict.Allowed -> Verdict.Rejected("closer_post_left")
                                else -> Verdict.Allowed
                            }
                        }
                    }, decompose = { intent, _ -> TaskNetwork.sequence(POST.call(intent.copy(hold = true, requireCover = false, requireShelter = false, legs = false, coveredBy = emptySet()))) }),
                ),
            ),
            PrimitiveTask(POST) { intent -> position("position", intent, Condition.test(TacticalFacts.ARRIVED, "post_not_reached") { it }) },
            // Each leg has its own identity: a leg ended on its post is done, and the next one is a
            // new search from there, not the same execution carried on.
            PrimitiveTask(LEG) { leg -> position("leg:${leg.number}", leg.intent, Condition.test(TacticalFacts.AT_POST, "leg_post_not_reached") { it }) },
            PrimitiveTask(PAUSE) { pause ->
                PrimitiveSpec(
                    "fire:${pause.leg}",
                    ActionSpec(FIRE_PAUSE, pause, setOf(ResourceKey(pause.actor, "motion")), timeoutTicks = FIRE_PAUSE_TIMEOUT),
                    TaskContract(maintain = Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it }),
                    planning = ownActor(pause.actor),
                )
            },
        ),
    )
    private const val NEIGHBOR_ATTEMPTS = 4

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
            TaskContract(start = if (intent.coveredBy.isNotEmpty()) Condition.test(TacticalFacts.SUPPORT_FIRING, "awaiting_support") { it } else Condition.ALWAYS, maintain = maintain, completed = completed),
            planning = ownActor(intent.actor),
        )
    }

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
 * @property reinforced the squad has a barracks that replaces its fallen, so its losses never
 *   make it withdraw (#57).
 * @property movers how many of its men advance by legs ([PositionIntent.legs]) before any
 *   strategy reshapes their tasks.
 * @property flanking some of its men are on the arc round a flank.
 * @property scattered a third of its men and at least two, aside from flankers and men who watch
 *   or search away from it on purpose, are farther from it than twice its perimeter radius.
 * @property gathered all those men are within its perimeter radius.
 * @property regrouping it holds [CombatStrategy.REGROUP].
 * @property underFire one of its men was hit within the last 13 s or so: [incoming] lasts 3 s
 *   after a hit and this 10 s more. A few distant riflemen hit too seldom to keep [incoming], and a
 *   strategy weighed by it was taken up and dropped every few seconds.
 */
data class CombatSituation(val contact: Boolean, val offensive: Boolean, val higherEnemy: Boolean, val incoming: Boolean, val readyFraction: Double, val lossFraction: Double, val continuation: Boolean, val reinforced: Boolean = false, val movers: Int = 0, val flanking: Boolean = false, val scattered: Boolean = false, val gathered: Boolean = false, val regrouping: Boolean = false, val underFire: Boolean = incoming)

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
        val base = strategy.rules.score(situation)
        val held = facts.value(TacticalFacts.MANNER) == strategy
        return UtilityScore(min(1.0, base + if (held) ADVANTAGE else 0.0), reason = strategy.rules.reason)
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
        val base = when (approach) {
            SquadApproach.FLANK -> (FLANK_BASE + (situation?.readyFraction ?: 0.0) * FLANK_READINESS - ((situation?.lossFraction ?: 0.0) - FLANK_LOSS_ALLOWANCE).coerceAtLeast(0.0) * FLANK_LOSSES).coerceIn(0.0, 1.0)
            SquadApproach.FRONTAL -> FRONTAL
        }
        val held = facts.value(TacticalFacts.APPROACH) == approach
        return UtilityScore(min(1.0, base + if (held) SquadManners.ADVANTAGE else 0.0), reason = approach.name.lowercase())
    }

    private const val FLANK_BASE = 0.5
    private const val FLANK_READINESS = 0.3

    // The approach is mostly chosen before contact, with nobody ready yet: losses up to 30% leave
    // the flank as it was, a third or more makes the squad come on frontally.
    private const val FLANK_LOSS_ALLOWANCE = 0.3
    private const val FLANK_LOSSES = 2.0
    private const val FRONTAL = 0.45
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

    /** Whether [rush]'s method applies: pairs need two movers nobody covers yet. */
    fun eligible(rush: TeamRush, mission: TeamMission) =
        when (rush) {
            TeamRush.TOGETHER -> Condition.ALWAYS
            TeamRush.PAIRS -> if (movers(mission).size >= 2) Condition.ALWAYS else Condition { Verdict.Rejected("no_pair") }
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
