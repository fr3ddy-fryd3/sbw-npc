package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.ActionKey
import com.sbwnpc.squad.ai.planning.ActionSpec
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.ai.planning.CompoundTask
import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.FactKey
import com.sbwnpc.squad.ai.planning.FactView
import com.sbwnpc.squad.ai.planning.Method
import com.sbwnpc.squad.ai.planning.PrimitiveSpec
import com.sbwnpc.squad.ai.planning.PrimitiveTask
import com.sbwnpc.squad.ai.planning.ResourceKey
import com.sbwnpc.squad.ai.planning.TaskContract
import com.sbwnpc.squad.ai.planning.TaskDefinition
import com.sbwnpc.squad.ai.planning.TaskKey
import com.sbwnpc.squad.ai.planning.TaskNetwork
import com.sbwnpc.squad.ai.planning.Verdict

/**
 * One mortar crew (#258): the men of one tube, or a man with none. [agent] is its team agent,
 * keyed so it survives the tube being packed and set up again, which makes a new tube entity.
 */
data class SupportCrew(val agent: AgentId, val members: List<AgentId>) {
    init {
        require(agent.level == AgentLevel.TEAM && members.isNotEmpty() && members.all { it.level == AgentLevel.NPC })
    }
}

/** Where a crew's tube is. */
enum class TubeState {
    /** Set up: the crew can serve it. */
    DEPLOYED,

    /** Packed and carried by one of the crew. */
    CARRIED,

    /** The crew has no tube: a man left over from a full crew, or one whose tube is gone. */
    NONE,
}

/** What one man of a crew does, the duty his crew's method gave him. */
enum class CrewWork {
    /** Lay and fire the tube at the crew's target, or keep it loaded with none. */
    OPERATE,

    /** Keep the tube loaded from his post beside it. */
    LOAD,

    /** Pack the tube, carry it to [CrewDuty.to] and set it up there. */
    DISPLACE,

    /** Keep up with the man carrying the tube. */
    FOLLOW,

    /** Walk to supply for shells, the other man keeping the tube. */
    FETCH_SHELLS,

    /** Walk to a free friendly tube and take a post at it. */
    TAKE_TUBE,

    /** Stand by his squad's nearest crew to take a post that frees up. */
    RESERVE,
}

/**
 * [actor]'s part in his crew's method: [work], the crew's [target] when it fires, and where a
 * displacement goes ([to]). The executor resolves the target to a point each time it lays.
 */
data class CrewDuty(val actor: AgentId, val work: CrewWork, val target: FireTarget? = null, val to: Point3? = null) {
    init {
        require(actor.level == AgentLevel.NPC)
    }
}

/** The facts a crew's plan reads (#258), built only for crew teams and their men. */
object CrewFacts {
    /** Where the crew's tube is. */
    val TUBE = FactKey("crew.tube", TubeState::class.java)

    /** A friendly working tube with a free post lies within reach of a crew with none. */
    val FREE_TUBE = FactKey("crew.free_tube", Boolean::class.javaObjectType)

    /** The order sets the tube up somewhere other than where it stands (a DEFEND or rally point). */
    val RELOCATE = FactKey("crew.relocate", Boolean::class.javaObjectType)

    /** Where [RELOCATE] sends the tube; observed only while it holds. */
    val RELOCATION = FactKey("crew.relocation", Point3::class.java)

    /** The crew's target has been out of the tube's reach for [FireSupport.DISPLACE_AFTER_TICKS]. */
    val OUT_OF_REACH = FactKey("crew.out_of_reach", Boolean::class.javaObjectType)

    /** Where to carry the tube for a target out of reach; observed only while [OUT_OF_REACH] holds. */
    val REACH_POINT = FactKey("crew.reach_point", Point3::class.java)

    /**
     * The man at the operator's post has no shells, nobody else at a post has any to take over
     * with, and supply exists. Read off the crew, not off who holds which post, so swapping the
     * posts to fetch shells does not flip it.
     */
    val SHELLS_OUT = FactKey("crew.shells_out", Boolean::class.javaObjectType)

    /** The crew's target, observed only while it has one. */
    val FIRE = FactKey("crew.fire", FireTarget::class.java)

    /** The man at the operator's post, who lays and fires and carries the tube. */
    val OPERATOR = FactKey("crew.operator", AgentId::class.java)

    /** He is free to serve the tube: no cover, evasion, peek, treatment, vehicle or hole has him. */
    val FREE = FactKey("crew.free", Boolean::class.javaObjectType)

    /** An enemy is within [FireSupport.SELF_DEFENCE_RANGE] of him: he defends himself first. */
    val THREATENED = FactKey("crew.threatened", Boolean::class.javaObjectType)
}

/**
 * The mortar crew's tasks (#258): its team's `team.fire_support` and each man's `npc.crew`.
 *
 * The crew's methods are tried in [ORDER]: a crew with no tube takes one or stands in reserve, a
 * tube the order sends elsewhere or that cannot reach its target is moved, a crew out of shells
 * fetches them, a crew with a target fires, and `stand_by` is the last resort.
 */
object FireSupport {
    val TEAM_TASK = TaskKey("team.fire_support", SupportCrew::class.java)
    val NPC_TASK = TaskKey("npc.crew", CrewDuty::class.java)

    /** One man's crew duty, carried out by the crew executor. */
    val ACTION = ActionKey("npc.crew_duty", CrewDuty::class.java)
    private val DUTY = TaskKey("npc.crew_duty", CrewDuty::class.java)

    /** How long a target stays out of reach before the crew moves the tube, 5 s, as before. */
    const val DISPLACE_AFTER_TICKS = 100L

    /** Nearer than this an enemy has him defend himself instead of serving the tube. */
    const val SELF_DEFENCE_RANGE = 6.0

    /** The crew's methods, first wins. */
    enum class Plan { TAKE_TUBE, STAND_RESERVE, DISPLACE, RESUPPLY, FIRE_MISSION, STAND_BY }

    val ORDER = Plan.entries

    /** Whether [plan] would apply on [facts], ignoring the plans before it. */
    fun wants(plan: Plan, facts: FactView): Verdict {
        val tube = facts.value(CrewFacts.TUBE) ?: return Verdict.Unknown(setOf(CrewFacts.TUBE.name))
        return when (plan) {
            Plan.TAKE_TUBE -> if (tube != TubeState.NONE) Verdict.Rejected("has_tube") else flag(facts, CrewFacts.FREE_TUBE, "no_free_tube")
            Plan.STAND_RESERVE -> if (tube == TubeState.NONE) Verdict.Allowed else Verdict.Rejected("has_tube")
            Plan.DISPLACE -> displace(tube, facts)
            Plan.RESUPPLY -> flag(facts, CrewFacts.SHELLS_OUT, "has_shells")
            Plan.FIRE_MISSION -> if (facts.value(CrewFacts.FIRE) != null) Verdict.Allowed else Verdict.Rejected("no_target")
            Plan.STAND_BY -> Verdict.Allowed
        }
    }

    /** A tube being carried goes on, and one the order sends elsewhere or that cannot reach is moved. */
    private fun displace(tube: TubeState, facts: FactView) = if (tube == TubeState.CARRIED || facts.value(CrewFacts.RELOCATE) == true || facts.value(CrewFacts.OUT_OF_REACH) == true) Verdict.Allowed else Verdict.Rejected("tube_in_place")

    /** Each of [mission]'s crews delegated its fire support, beside whatever its ground teams do. */
    fun delegations(mission: SquadMission) = mission.support.map { DelegationTask(it.agent, TEAM_TASK.call(it)) }

    /** [plan] wants to and no plan before it in [ORDER] does. */
    fun applies(plan: Plan) =
        Condition { facts ->
            for (earlier in ORDER.takeWhile { it != plan }) {
                when (val verdict = wants(earlier, facts)) {
                    Verdict.Allowed -> return@Condition Verdict.Rejected("preempted_by_${earlier.name.lowercase()}")
                    is Verdict.Unknown -> return@Condition verdict
                    is Verdict.Rejected -> Unit
                }
            }
            wants(plan, facts)
        }

    /** The method id of [plan] on [facts]; a fire mission carries why it fires there. */
    fun methodId(plan: Plan, facts: FactView? = null) = if (plan == Plan.FIRE_MISSION) "fire_mission/${facts?.value(CrewFacts.FIRE)?.source?.name?.lowercase() ?: "none"}" else plan.name.lowercase()

    /** Each man's duty under [plan]: the operator's part, and the rest's. */
    fun duties(plan: Plan, crew: SupportCrew, facts: FactView): List<CrewDuty> {
        val operator = facts.value(CrewFacts.OPERATOR)?.takeIf { it in crew.members } ?: crew.members.first()
        val others = crew.members - operator
        fun all(work: CrewWork) = crew.members.map { CrewDuty(it, work) }
        return when (plan) {
            Plan.TAKE_TUBE -> all(CrewWork.TAKE_TUBE)

            Plan.STAND_RESERVE -> all(CrewWork.RESERVE)

            Plan.DISPLACE -> {
                val to = facts.value(CrewFacts.RELOCATION) ?: facts.value(CrewFacts.REACH_POINT)
                listOf(CrewDuty(operator, CrewWork.DISPLACE, to = to)) + others.map { CrewDuty(it, CrewWork.FOLLOW) }
            }

            Plan.RESUPPLY -> listOf(CrewDuty(operator, CrewWork.FETCH_SHELLS)) + others.map { CrewDuty(it, CrewWork.OPERATE) }

            Plan.FIRE_MISSION -> listOf(CrewDuty(operator, CrewWork.OPERATE, facts.value(CrewFacts.FIRE))) + others.map { CrewDuty(it, CrewWork.LOAD) }

            Plan.STAND_BY -> listOf(CrewDuty(operator, CrewWork.OPERATE)) + others.map { CrewDuty(it, CrewWork.LOAD) }
        }
    }

    /** The identity of [duty]'s action: a new duty or a new target is a new action. */
    fun identity(duty: CrewDuty) = "crew:${duty.work.name.lowercase()}:${duty.target?.key ?: ""}:${duty.to ?: ""}"

    /** He may serve: free of every reaction that takes his feet, and not defending himself. */
    fun maintain() =
        Condition.all(
            Condition.test(CrewFacts.FREE, "higher_priority_action") { it },
            Condition.test(CrewFacts.THREATENED, "self_defence") { !it },
        )

    /** The team and NPC tasks, joined into [TacticalDomain]'s; [children] delegates to the men. */
    fun tasks(children: (List<DelegationTask>) -> TaskNetwork): Pair<List<TaskDefinition<*>>, List<TaskDefinition<*>>> {
        val team = listOf(
            CompoundTask(
                TEAM_TASK,
                ORDER.map { plan ->
                    Method<SupportCrew>(plan.name.lowercase(), eligible = { applies(plan) }, decompose = { crew, facts ->
                        children(duties(plan, crew, facts).map { DelegationTask(it.actor, NPC_TASK.call(it)) })
                    })
                },
            ),
        )
        val npc = listOf(
            CompoundTask(
                NPC_TASK,
                listOf(
                    // An enemy at arm's length: he fights it off through the engage every man has
                    // (#251), never forward, and serves again once it is gone.
                    Method<CrewDuty>("self_defence", eligible = { flagCondition(CrewFacts.THREATENED, "not_threatened") }, decompose = { duty, _ ->
                        TaskNetwork.sequence(TacticalDomain.ENGAGE_TASK.call(EngageTask(duty.actor, forward = false)))
                    }),
                    Method<CrewDuty>("serve", eligible = { Condition.test(CrewFacts.THREATENED, "self_defence") { !it } }, decompose = { duty, _ -> TaskNetwork.sequence(DUTY.call(duty)) }),
                ),
            ),
            PrimitiveTask(DUTY) { duty ->
                PrimitiveSpec(
                    identity(duty),
                    ActionSpec(ACTION, duty, setOf(ResourceKey(duty.actor, "motion")), timeoutTicks = Long.MAX_VALUE),
                    TaskContract(maintain = maintain()),
                )
            },
        )
        return team to npc
    }
}

private fun flag(facts: FactView, key: FactKey<Boolean>, reason: String): Verdict = facts.value(key)?.let { if (it) Verdict.Allowed else Verdict.Rejected(reason) } ?: Verdict.Unknown(setOf(key.name))

private fun flagCondition(key: FactKey<Boolean>, reason: String) = Condition.test(key, reason) { it }
