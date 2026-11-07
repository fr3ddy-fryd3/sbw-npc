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

/**
 * One helicopter (#260): its NPC [pilot] in seat 0 and the NPC [gunners] at its turret. [agent] is
 * its team agent, keyed by the aircraft. A player flying it leaves [pilot] null: the plan still
 * works his gunners.
 */
data class Aircraft(val agent: AgentId, val pilot: AgentId?, val gunners: List<AgentId>) {
    init {
        require(agent.level == AgentLevel.TEAM && (pilot != null || gunners.isNotEmpty()) && (listOfNotNull(pilot) + gunners).all { it.level == AgentLevel.NPC })
    }
}

/** What the airframe is for. */
enum class Airframe {
    /** A turret aircraft (Mi-28): it lays its gun on a target from a standoff hover. */
    GUNSHIP,

    /** A bench aircraft (AH-6): it carries riders and flies its benches around a fight. */
    TRANSPORT,
}

/** The order the aircraft's squad is under, [NONE] with no squad, so a squadless aircraft still reads one. */
enum class AirOrder { NONE, ATTACK, DEFEND, PATROL, MOVE, RETREAT, OTHER }

/** How the pilot flies, the mode his aircraft's method gave him. Where exactly is execution's. */
enum class FlightMode {
    /** Clear off from the last fight and land. */
    RETIRE,

    /** Land at the squad's point. */
    LAND,

    /** Circle the confirmed contact, or the objective, with an occupied bench toward the centre. */
    ORBIT,

    /** Hover on the standoff ring around [FlightDuty.target]. */
    STANDOFF,

    /** Hover on the standoff ring around the last contact. */
    HOLD_CONTACT,

    /** Circle the squad's point slowly and low. */
    LOITER,
}

/** The pilot's part: how to fly, and the aircraft's target a standoff is held from. */
data class FlightDuty(val actor: AgentId, val mode: FlightMode, val target: UUID? = null) {
    init {
        require(actor.level == AgentLevel.NPC)
    }
}

/** A gunner's part: the aircraft's target, which his own sight lays the gun on. */
data class GunDuty(val actor: AgentId, val target: UUID? = null) {
    init {
        require(actor.level == AgentLevel.NPC)
    }
}

/** The facts an aircraft's plan reads (#260), built only for aircraft teams and their men. */
object AirFacts {
    val AIRFRAME = FactKey("air.airframe", Airframe::class.java)

    /** It can fly and someone aboard can fight: anything less has it break off. */
    val FIT = FactKey("air.fit", Boolean::class.javaObjectType)
    val ORDER = FactKey("air.order", AirOrder::class.java)

    /** Its squad has a point to fly to: the bench orbit needs one. */
    val HAS_OBJECTIVE = FactKey("air.has_objective", Boolean::class.javaObjectType)

    /** The aircraft's one target (#261): the turret gunner's, else the pilot's; observed only while it has one. */
    val TARGET = FactKey("air.target", UUID::class.java)

    /** A target was seen within [AirSupport.CONTACT_HOLD_TICKS], or the ordered focus of an attack lives. */
    val IN_CONTACT = FactKey("air.in_contact", Boolean::class.javaObjectType)

    /** It has met the enemy since it boarded: an attack that never did is still on its way. */
    val MET_ENEMY = FactKey("air.met_enemy", Boolean::class.javaObjectType)

    /** He sits in his seat of this aircraft. */
    val ABOARD = FactKey("air.aboard", Boolean::class.javaObjectType)
}

/**
 * The helicopter's tasks (#260, #261): its team's `team.air`, the pilot's `npc.fly` and each
 * gunner's `npc.gun`.
 *
 * The methods are tried in [ORDER], first wins, and reproduce the pilot's former mission choice:
 * an aircraft that cannot fight breaks off, an order to move or fall back lands it at the squad's
 * point, an AH-6 attack orbits, a Mi-28 holds a standoff from its target and then from the last
 * contact, a defence or patrol loiters (and so does a Mi-28 attack that has not met the enemy yet),
 * a Mi-28 whose fight has gone quiet withdraws, and `land` is the last resort.
 */
object AirSupport {
    val TEAM_TASK = TaskKey("team.air", Aircraft::class.java)
    val FLY_TASK = TaskKey("npc.fly", FlightDuty::class.java)
    val GUN_TASK = TaskKey("npc.gun", GunDuty::class.java)

    /** The pilot's flying, carried out by the flight executor. */
    val FLIGHT = ActionKey("npc.flight", FlightDuty::class.java)

    /** A gunner's turret, carried out by the turret executor. */
    val TURRET = ActionKey("npc.turret", GunDuty::class.java)
    private val FLIGHT_TASK = TaskKey("npc.flight", FlightDuty::class.java)
    private val TURRET_TASK = TaskKey("npc.turret", GunDuty::class.java)

    /** How long the area stays hot after the last confirmed target, 10 s, as before. */
    const val CONTACT_HOLD_TICKS = 200L

    /** The aircraft's methods, first wins. */
    enum class Plan(val mode: FlightMode) {
        BREAK_OFF(FlightMode.RETIRE),
        RETURN_HOME(FlightMode.LAND),
        BENCH_ORBIT(FlightMode.ORBIT),
        STANDOFF(FlightMode.STANDOFF),
        HOLD_CONTACT(FlightMode.HOLD_CONTACT),
        LOITER(FlightMode.LOITER),
        WITHDRAW(FlightMode.RETIRE),
        LAND(FlightMode.LAND),
    }

    val ORDER = Plan.entries

    /** Whether [plan] would apply on [facts], ignoring the plans before it. */
    fun wants(plan: Plan, facts: FactView): Verdict {
        val frame = facts.value(AirFacts.AIRFRAME) ?: return Verdict.Unknown(setOf(AirFacts.AIRFRAME.name))
        val order = facts.value(AirFacts.ORDER) ?: return Verdict.Unknown(setOf(AirFacts.ORDER.name))
        val gunship = frame == Airframe.GUNSHIP
        return when (plan) {
            Plan.BREAK_OFF -> facts.value(AirFacts.FIT)?.let { if (it) Verdict.Rejected("fit") else Verdict.Allowed } ?: Verdict.Unknown(setOf(AirFacts.FIT.name))
            Plan.RETURN_HOME, Plan.BENCH_ORBIT, Plan.LOITER -> orderWants(plan, order, gunship, facts)
            Plan.LAND -> Verdict.Allowed
            else -> if (gunship) gunshipWants(plan, order, facts) else Verdict.Rejected("no_turret")
        }
    }

    /** The methods the order alone decides: a recall, the AH-6's attack orbit, and the circling of a held point. */
    private fun orderWants(plan: Plan, order: AirOrder, gunship: Boolean, facts: FactView): Verdict =
        when (plan) {
            Plan.RETURN_HOME -> allow(order == AirOrder.MOVE || order == AirOrder.RETREAT, "not_recalled")
            Plan.BENCH_ORBIT -> allow(!gunship && order == AirOrder.ATTACK && facts.value(AirFacts.HAS_OBJECTIVE) == true, "no_bench_attack")
            Plan.LOITER -> allow(order == AirOrder.DEFEND || order == AirOrder.PATROL || (gunship && order == AirOrder.ATTACK && facts.value(AirFacts.MET_ENEMY) != true), "nothing_to_circle")
            else -> error("not an order method: $plan")
        }

    /** The methods only a turret aircraft has: a standoff from its target or its last contact, and the withdrawal once that goes quiet. */
    private fun gunshipWants(plan: Plan, order: AirOrder, facts: FactView): Verdict =
        when (plan) {
            Plan.STANDOFF -> allow(facts.value(AirFacts.TARGET) != null, "no_target")
            Plan.HOLD_CONTACT -> allow(facts.value(AirFacts.IN_CONTACT) == true, "contact_quiet")
            Plan.WITHDRAW -> allow(order == AirOrder.ATTACK, "no_fight_to_leave")
            else -> error("not a gunship method: $plan")
        }

    private fun allow(allowed: Boolean, reason: String): Verdict = if (allowed) Verdict.Allowed else Verdict.Rejected(reason)

    /** Each of [mission]'s aircraft delegated its flight, beside whatever its ground teams do. */
    fun delegations(mission: SquadMission) = mission.air.map { DelegationTask(it.agent, TEAM_TASK.call(it)) }

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

    /** The pilot's duty and the gunners' under [plan]: every man works on the aircraft's one target. */
    fun duties(plan: Plan, aircraft: Aircraft, facts: FactView): Pair<FlightDuty?, List<GunDuty>> {
        val target = facts.value(AirFacts.TARGET)
        val flight = aircraft.pilot?.let { FlightDuty(it, plan.mode, target.takeIf { plan == Plan.STANDOFF }) }
        return flight to aircraft.gunners.map { GunDuty(it, target) }
    }

    /** He keeps his seat: that alone holds his action, which must keep flying through every replan. */
    private fun aboard() = Condition.test(AirFacts.ABOARD, "left_seat") { it }

    /** The team and NPC tasks, joined into [TacticalDomain]'s; [children] delegates to the men. */
    fun tasks(children: (List<DelegationTask>) -> TaskNetwork): Pair<List<TaskDefinition<*>>, List<TaskDefinition<*>>> {
        val team = listOf(
            CompoundTask(
                TEAM_TASK,
                ORDER.map { plan ->
                    Method<Aircraft>(plan.name.lowercase(), eligible = { applies(plan) }, decompose = { aircraft, facts ->
                        val (flight, guns) = duties(plan, aircraft, facts)
                        children(listOfNotNull(flight?.let { DelegationTask(it.actor, FLY_TASK.call(it)) }) + guns.map { DelegationTask(it.actor, GUN_TASK.call(it)) })
                    })
                },
            ),
        )
        val npc = listOf(
            CompoundTask(FLY_TASK, listOf(Method<FlightDuty>("fly", decompose = { duty, _ -> TaskNetwork.sequence(FLIGHT_TASK.call(duty)) }))),
            CompoundTask(GUN_TASK, listOf(Method<GunDuty>("gun", decompose = { duty, _ -> TaskNetwork.sequence(TURRET_TASK.call(duty)) }))),
            // One identity per man: a new mode or target revises the running action in place, as
            // a restart would cut an aircraft's controls in the air.
            PrimitiveTask(FLIGHT_TASK) { duty ->
                PrimitiveSpec(
                    "flight:${duty.actor.id}",
                    ActionSpec(FLIGHT, duty, setOf(ResourceKey(duty.actor, "motion")), timeoutTicks = Long.MAX_VALUE),
                    TaskContract(maintain = aboard()),
                )
            },
            PrimitiveTask(TURRET_TASK) { duty ->
                PrimitiveSpec(
                    "turret:${duty.actor.id}",
                    ActionSpec(TURRET, duty, setOf(ResourceKey(duty.actor, "motion")), timeoutTicks = Long.MAX_VALUE),
                    TaskContract(maintain = aboard()),
                )
            },
        )
        return team to npc
    }
}
