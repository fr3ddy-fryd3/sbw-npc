package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.DelegationTask
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
import java.util.UUID

/** One tank (#185): its NPC [driver], at the wheel of his own tank. [agent] is its team agent, keyed by the tank. */
data class Tank(val agent: AgentId, val driver: AgentId) {
    init {
        require(agent.level == AgentLevel.TEAM && driver.level == AgentLevel.NPC)
    }
}

/** How the driver drives, the mode his tank's method gave him. Where he steers is execution's. */
enum class DriveMode {
    /** Keep the pace of the [DriveDuty.escorted] infantry: never run far ahead of its leading man. */
    PACE,

    /** Drive for the squad's point at the tank's own speed. */
    FREE,
}

/** The driver's part: how to drive, and whose infantry a [DriveMode.PACE] keeps to. */
data class DriveDuty(val actor: AgentId, val mode: DriveMode, val escorted: List<UUID> = emptyList()) {
    init {
        require(actor.level == AgentLevel.NPC)
    }
}

/** The facts a tank's plan reads (#185), built only for tank teams and their drivers. */
object TankFacts {
    /** The infantry squads attacking under the tank's squad's command; observed only while there are any. */
    val ESCORTED = FactKey("tank.escorted", EscortedSquads::class.java)

    /** That command has met the enemy: once it has, the escort is over for the rest of the command. */
    val ENGAGED = FactKey("tank.engaged", Boolean::class.javaObjectType)

    /** He sits at the wheel of his own tank. */
    val AT_WHEEL = FactKey("tank.at_wheel", Boolean::class.javaObjectType)
}

/** The ids of the infantry squads a tank escorts, as one fact value. */
data class EscortedSquads(val squads: List<UUID>)

/**
 * The tank's tasks (#185): its team's `team.tank` and the driver's `npc.drive`.
 *
 * Sent with infantry in one group attack, a tank that has not met the enemy keeps the infantry's
 * pace instead of racing ahead alone (`escort`); otherwise it drives at its own (`drive`, the last
 * resort). The gun and the seat stay with their executors.
 */
object TankSupport {
    val TEAM_TASK = TaskKey("team.tank", Tank::class.java)
    val DRIVE_TASK = TaskKey("npc.drive", DriveDuty::class.java)

    /** The driver's driving, carried out by the vehicle's drive executor. */
    val DRIVE = ActionKey("npc.tank_drive", DriveDuty::class.java)
    private val DRIVE_ACTION_TASK = TaskKey("npc.tank_drive", DriveDuty::class.java)

    /** How far a tank keeping pace may run ahead of the leading infantryman before it lets the engine idle. */
    const val ESCORT_LEAD = 8.0

    /** The tank's methods, first wins. */
    enum class Plan(val mode: DriveMode) {
        ESCORT(DriveMode.PACE),
        DRIVE(DriveMode.FREE),
    }

    val ORDER = Plan.entries

    /** Whether [plan] would apply on [facts], ignoring the plans before it. */
    fun wants(plan: Plan, facts: FactView): Verdict =
        when (plan) {
            Plan.ESCORT -> escort(facts)
            Plan.DRIVE -> Verdict.Allowed
        }

    /** Infantry to escort that has not met the enemy yet. */
    private fun escort(facts: FactView): Verdict {
        if (facts.value(TankFacts.ESCORTED) == null) return Verdict.Rejected("no_infantry")
        val engaged = facts.value(TankFacts.ENGAGED) ?: return Verdict.Unknown(setOf(TankFacts.ENGAGED.name))
        return if (engaged) Verdict.Rejected("engaged") else Verdict.Allowed
    }

    /** Each of [mission]'s tanks delegated its driving, beside whatever else the squad does. */
    fun delegations(mission: SquadMission) = mission.tanks.map { DelegationTask(it.agent, TEAM_TASK.call(it)) }

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

    /** The driver's duty under [plan]. */
    fun duty(plan: Plan, tank: Tank, facts: FactView) = DriveDuty(tank.driver, plan.mode, if (plan == Plan.ESCORT) facts.value(TankFacts.ESCORTED)?.squads.orEmpty() else emptyList())

    /** The team and NPC tasks, joined into [TacticalDomain]'s; [children] delegates to the driver. */
    fun tasks(children: (List<DelegationTask>) -> TaskNetwork): Pair<List<TaskDefinition<*>>, List<TaskDefinition<*>>> {
        val team = listOf(
            CompoundTask(
                TEAM_TASK,
                ORDER.map { plan ->
                    Method<Tank>(plan.name.lowercase(), eligible = { applies(plan) }, decompose = { tank, facts ->
                        val duty = duty(plan, tank, facts)
                        children(listOf(DelegationTask(duty.actor, DRIVE_TASK.call(duty))))
                    })
                },
            ),
        )
        val npc = listOf(
            CompoundTask(DRIVE_TASK, listOf(Method<DriveDuty>("drive", decompose = { duty, _ -> TaskNetwork.sequence(DRIVE_ACTION_TASK.call(duty)) }))),
            // One identity per man: a new mode revises the running action in place.
            PrimitiveTask(DRIVE_ACTION_TASK) { duty ->
                PrimitiveSpec(
                    "tank_drive:${duty.actor.id}",
                    ActionSpec(DRIVE, duty, setOf(ResourceKey(duty.actor, "motion")), timeoutTicks = Long.MAX_VALUE),
                    TaskContract(maintain = Condition.test(TankFacts.AT_WHEEL, "left_wheel") { it }),
                )
            },
        )
        return team to npc
    }
}
