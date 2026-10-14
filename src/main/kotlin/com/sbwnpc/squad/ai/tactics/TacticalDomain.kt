package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationAction
import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*

enum class CombatDuty { FIRE, MOVE, REAR_GUARD, OBSERVE, SEARCH, FOLLOW_ORDER }
enum class CombatStrategy { ADVANCE, HOLD, GAIN_HEIGHT, WITHDRAW }
data class PositionIntent(val actor: AgentId, val duty: CombatDuty, val origin: Point3, val anchor: Point3, val focus: Point3?, val requireCover: Boolean = false, val requireShelter: Boolean = false, val minimumHeight: Double? = null, val exposureWeight: Double = 1.0, val inspection: Point3? = null) {
    init {
        require(actor.level == AgentLevel.NPC && exposureWeight.isFinite() && exposureWeight >= 0)
    }
}
data class TeamMission(val agent: AgentId, val members: List<PositionIntent>)
data class SquadMission(val agent: AgentId, val teams: List<TeamMission>)
data class AreaControlTask(val operation: java.util.UUID, val objective: Point3, val commands: Map<java.util.UUID, java.util.UUID>)
data class OperationMission(val squads: List<SquadMission>, val secure: AreaControlTask? = null)

/** Typed observations are scoped by FactSnapshot.owner; no world queries live in this domain. */
data class LocalPositionFailure(val anchor: Point3, val failedAt: Long, val attempts: Int, val rejections: Map<String, Int>)

object TacticalFacts {
    val POST_FAILURE = FactKey("post.local_failure", LocalPositionFailure::class.java)
    val AREA_SECURED = FactKey("operation.area_secured", Boolean::class.javaObjectType)
    val MOVABLE = FactKey("actor.movable", Boolean::class.javaObjectType)
    val COVER = FactKey("sector.actual_cover", Boolean::class.javaObjectType)
    val ARRIVED = FactKey("post.actual_arrival", Boolean::class.javaObjectType)
    val STRATEGY = FactKey("squad.strategy", CombatStrategy::class.java)
}

object TacticalDomain {
    val OPERATION = TaskKey("operation.execute", OperationMission::class.java)
    val SQUAD = TaskKey("squad.execute", SquadMission::class.java)
    val TEAM = TaskKey("team.execute", TeamMission::class.java)
    val NPC = TaskKey("npc.execute", PositionIntent::class.java)
    val SECURE = ActionKey("operation.secure_area", AreaControlTask::class.java)
    private val SECURE_TASK = TaskKey("operation.hold_and_inspect", AreaControlTask::class.java)
    val POSITION = ActionKey("npc.position", PositionIntent::class.java)
    private val DELEGATE = TaskKey("delegate.task", DelegationTask::class.java)

    private fun delegate() =
        PrimitiveTask(DELEGATE) { task ->
            PrimitiveSpec(task.child.id.toString(), ActionSpec(DelegationAction.KEY, task.copy(continuous = true), setOf(ResourceKey(task.child, "command")), timeoutTicks = Long.MAX_VALUE), TaskContract())
        }
    private fun children(tasks: List<DelegationTask>) = TaskNetwork(tasks.map { NetworkNode(it.child.id.toString(), DELEGATE.call(it)) })

    val operation = HtnDomain(
        AgentLevel.OPERATION,
        listOf(
            CompoundTask(
                OPERATION,
                listOf(
                    Method("coordinate", decompose = { mission, _ ->
                        val nodes = mission.squads.map { NetworkNode(it.agent.id.toString(), DELEGATE.call(DelegationTask(it.agent, SQUAD.call(it)))) }
                        TaskNetwork(nodes + listOfNotNull(mission.secure?.let { NetworkNode("secure", SECURE_TASK.call(it)) }))
                    }),
                ),
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
                        strategy.name.lowercase(),
                        eligible = { Condition.test(TacticalFacts.STRATEGY, "other_strategy") { it == strategy } },
                        decompose = { mission, _ ->
                            val tasks = mission.teams.map { team ->
                                val selected = team.copy(
                                    members = team.members.map { intent ->
                                        when (strategy) {
                                            CombatStrategy.HOLD -> if (intent.duty == CombatDuty.MOVE) intent.copy(duty = CombatDuty.FIRE, anchor = intent.origin, requireCover = false) else intent
                                            CombatStrategy.GAIN_HEIGHT -> intent.copy(minimumHeight = intent.origin.y - 2, requireShelter = true)
                                            CombatStrategy.WITHDRAW -> intent.copy(requireCover = false, requireShelter = true)
                                            CombatStrategy.ADVANCE -> intent
                                        }
                                    },
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
            CompoundTask(TEAM, listOf(Method("distribute", decompose = { mission, _ -> children(mission.members.map { DelegationTask(it.actor, NPC.call(it)) }) }))),
            delegate(),
        ),
    )
    private val POST = TaskKey("npc.post", PositionIntent::class.java)
    val npc = HtnDomain(
        AgentLevel.NPC,
        listOf(
            CompoundTask(
                NPC,
                listOf(
                    Method("preferred_post", eligible = { intent ->
                        Condition { facts ->
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            if (failure?.anchor == intent.anchor) Verdict.Rejected("preferred_query_exhausted") else Verdict.Allowed
                        }
                    }, decompose = { intent, _ -> TaskNetwork.sequence(POST.call(intent)) }),
                    Method("neighbor_post", eligible = { intent ->
                        Condition { facts ->
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            if (failure?.anchor == intent.anchor && failure.attempts <= 4) Verdict.Allowed else Verdict.Rejected("local_alternatives_exhausted")
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
                        TaskNetwork.sequence(POST.call(intent.copy(anchor = anchor)))
                    }),
                ),
            ),
            PrimitiveTask(POST) { intent ->
                val maintain = Condition.all(
                    Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it },
                    if (intent.requireCover) Condition.test(TacticalFacts.COVER, "cover_lost") { it } else Condition.ALWAYS,
                )
                PrimitiveSpec(
                    "position",
                    ActionSpec(
                        POSITION,
                        intent,
                        if (intent.duty == CombatDuty.FOLLOW_ORDER) emptySet() else setOf(ResourceKey(intent.actor, "motion")),
                        timeoutTicks = if (intent.duty == CombatDuty.FOLLOW_ORDER) Long.MAX_VALUE else 1200,
                    ),
                    TaskContract(maintain = maintain, completed = Condition.test(TacticalFacts.ARRIVED, "post_not_reached") { it }),
                    planning = Condition { facts -> if (facts.owner == intent.actor) Verdict.Allowed else Verdict.Rejected("foreign_actor_intent") },
                )
            },
        ),
    )
    fun at(level: AgentLevel) =
        when (level) {
            AgentLevel.OPERATION -> operation
            AgentLevel.SQUAD -> squad
            AgentLevel.TEAM -> team
            AgentLevel.NPC -> npc
        }
}

data class CombatSituation(val contact: Boolean, val offensive: Boolean, val higherEnemy: Boolean, val incoming: Boolean, val readyFraction: Double, val lossFraction: Double, val continuation: Boolean)
object StrategyEvaluation {
    fun evaluate(facts: FactSnapshot, situation: CombatSituation): DecisionEvaluation<CombatStrategy> {
        fun candidate(strategy: CombatStrategy, allowed: Boolean, score: Double, reason: String) =
            DecisionCandidate(
                strategy.name,
                strategy,
                Condition { if (allowed) Verdict.Allowed else Verdict.Rejected(reason) },
                { UtilityScore(score, reason = reason) },
            )
        return UtilityEvaluation.rank(
            facts,
            listOf(
                candidate(CombatStrategy.ADVANCE, (situation.offensive && situation.continuation) || !situation.contact, 0.55 + situation.readyFraction * 0.2, "advance_capacity"),
                candidate(CombatStrategy.HOLD, true, if (!situation.offensive && situation.contact) 0.9 else 0.4, "hold_sector"),
                candidate(CombatStrategy.GAIN_HEIGHT, situation.contact && situation.higherEnemy && situation.offensive, 0.93, "enemy_above"),
                candidate(CombatStrategy.WITHDRAW, situation.incoming && situation.lossFraction >= 0.4 && !situation.continuation, 0.95, "losses_without_continuation"),
            ),
        )
    }
}
