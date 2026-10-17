package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationAction
import com.sbwnpc.squad.ai.coordination.DelegationTask
import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import kotlin.math.max

enum class CombatDuty { FIRE, MOVE, REAR_GUARD, OBSERVE, SEARCH, FOLLOW_ORDER }
enum class CombatStrategy { ADVANCE, HOLD, GAIN_HEIGHT, WITHDRAW }
data class PositionIntent(val actor: AgentId, val duty: CombatDuty, val origin: Point3, val anchor: Point3, val focus: Point3?, val requireCover: Boolean = false, val requireShelter: Boolean = false, val minimumHeight: Double? = null, val exposureWeight: Double = 1.0, val inspection: Point3? = null, val elevationWeight: Double = 0.0, val assault: Boolean = false, val standoff: Double = 0.0) {
    init {
        require(actor.level == AgentLevel.NPC && exposureWeight.isFinite() && exposureWeight >= 0 && elevationWeight.isFinite() && elevationWeight >= 0)
    }
}
data class TeamMission(val agent: AgentId, val members: List<PositionIntent>)
data class SquadMission(val agent: AgentId, val teams: List<TeamMission>)
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
    val STRATEGY = FactKey("squad.strategy", CombatStrategy::class.java)
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
    private val DELEGATE = TaskKey("delegate.task", DelegationTask::class.java)

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
                    }, decompose = { mission, _ -> coordinate(mission, if (phase == AttackPhase.DEPLOY) AttackRoles.NONE else mission.attack) })
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
                        strategy.name.lowercase(),
                        eligible = { Condition.test(TacticalFacts.STRATEGY, "other_strategy") { it == strategy } },
                        decompose = { mission, _ ->
                            val tasks = mission.teams.map { team ->
                                val selected = team.copy(
                                    // A member assaulting for the operation keeps its role whatever the squad's own strategy.
                                    members = team.members.map { intent ->
                                        if (intent.assault) return@map intent
                                        when (strategy) {
                                            CombatStrategy.HOLD -> if (intent.duty == CombatDuty.MOVE) intent.copy(duty = CombatDuty.FIRE, anchor = intent.origin, requireCover = false) else intent

                                            CombatStrategy.GAIN_HEIGHT -> intent.copy(minimumHeight = intent.origin.y - 2, elevationWeight = 3.0)

                                            CombatStrategy.WITHDRAW -> if (intent.duty != CombatDuty.FOLLOW_ORDER) {
                                                val dx = intent.origin.x - (intent.focus?.x ?: intent.anchor.x)
                                                val dz = intent.origin.z - (intent.focus?.z ?: intent.anchor.z + 1)
                                                val length = kotlin.math.hypot(dx, dz).coerceAtLeast(0.01)
                                                intent.copy(duty = CombatDuty.MOVE, anchor = Point3(intent.origin.x + dx / length * 48, intent.origin.y, intent.origin.z + dz / length * 48), requireCover = false, requireShelter = false, exposureWeight = 10.0)
                                            } else {
                                                intent
                                            }

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
                        TaskNetwork.sequence(POST.call(intent.copy(anchor = anchor)))
                    }),
                    // Terrain hides the enemy from the whole neighbourhood of the anchor: look for a
                    // line of fire closer to the enemy or along the front instead of retrying the
                    // same blind spot forever.
                    Method("closer_post", eligible = { intent ->
                        Condition { facts ->
                            val failure = facts.value(TacticalFacts.POST_FAILURE)
                            val step = (failure?.attempts ?: 0) - NEIGHBOR_ATTEMPTS - 1
                            when {
                                failure?.anchor != intent.anchor || step < 0 -> Verdict.Rejected("neighbors_not_exhausted")
                                !blind(failure) -> Verdict.Rejected("not_blind")
                                step >= closerAnchors(intent).size -> Verdict.Rejected("closer_alternatives_exhausted")
                                else -> Verdict.Allowed
                            }
                        }
                    }, decompose = { intent, facts ->
                        val step = facts.value(TacticalFacts.POST_FAILURE)!!.attempts - NEIGHBOR_ATTEMPTS - 1
                        TaskNetwork.sequence(POST.call(intent.copy(anchor = closerAnchors(intent)[step])))
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
    private const val NEIGHBOR_ATTEMPTS = 4

    /** Steps, forward towards the enemy and sideways along the front, tried once the neighbours fail. */
    private val CLOSER_STEPS = listOf(12.0 to 0.0, 24.0 to 0.0, 12.0 to 16.0, 12.0 to -16.0, 24.0 to 16.0, 24.0 to -16.0, 36.0 to 0.0)

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
 * One squad's fight as [StrategyEvaluation] sees it.
 *
 * @property reinforced the squad has a barracks that replaces its fallen, so its losses never
 *   make it withdraw (#57).
 */
data class CombatSituation(val contact: Boolean, val offensive: Boolean, val higherEnemy: Boolean, val incoming: Boolean, val readyFraction: Double, val lossFraction: Double, val continuation: Boolean, val reinforced: Boolean = false)
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
                candidate(CombatStrategy.WITHDRAW, situation.incoming && situation.lossFraction >= 0.4 && !situation.continuation && !situation.reinforced, 0.95, "losses_without_continuation"),
            ),
        )
    }
}
