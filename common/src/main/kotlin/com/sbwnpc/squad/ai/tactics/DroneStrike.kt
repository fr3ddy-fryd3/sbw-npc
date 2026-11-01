package com.sbwnpc.squad.ai.tactics

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
import java.util.UUID

/** Where a strike target came from, in the order the plan prefers them (#259). */
enum class StrikeSource {
    /** The focus of his squad's ATTACK. */
    FOCUS,

    /** The point of his squad's ATTACK. */
    OBJECTIVE,

    /** The enemy his own team is fighting: the team's operator works for it first. */
    TEAM,

    /** An enemy he saw himself, or the drone's camera sees while it is up. */
    SPOTTED,

    /** An enemy his side reported. */
    REPORTED,
}

/**
 * A strike target: a man ([entity]), or a fixed point when [entity] is null, last known at [at].
 * A man is the same target wherever he stands: his every step must not replan the strike, and the
 * drone follows him by sight, not by the plan's copy of where he was.
 */
class StrikePoint(val entity: UUID?, val at: Point3) {
    override fun equals(other: Any?) = other is StrikePoint && other.entity == entity && (entity != null || other.at == at)

    override fun hashCode() = entity?.hashCode() ?: at.hashCode()

    override fun toString() = "StrikePoint(${entity ?: at})"
}

/** The strike targets on offer this look, at most one per source. */
data class StrikeOptions(val bySource: Map<StrikeSource, StrikePoint>) {
    companion object {
        val NONE = StrikeOptions(emptyMap())
    }
}

/** Why he keeps his drone in its case, [NONE] when he may launch one. */
enum class DroneHold {
    /** Nothing keeps it: he may launch. */
    NONE,

    /** His squad falls back: nothing new goes up. */
    RETREAT,

    /** He has flown all he carried; a barracks refills him. */
    NO_DRONES,

    /** 10–15 s after a flight before the next. */
    COOLDOWN,

    /** An enemy within arm's reach: he shoots, he does not launch. */
    THREAT,

    /** Cover, evasion or a peek has his feet: a squadless man's, whose cover no plan decides. */
    BUSY,
}

/** One drone strike's task, the root of `npc.strike`. */
data class StrikeCall(val actor: AgentId) {
    init {
        require(actor.level == AgentLevel.NPC)
    }
}

/**
 * What his drone does: fly at [target], or home with [target] null. [source] names the method
 * that chose it, for the trace; where he launches from (here, or a walk to open sky) is the
 * station's to find, as the launch column is mechanics, not a choice.
 */
data class StrikeDuty(val actor: AgentId, val target: StrikePoint?, val source: StrikeSource?) {
    init {
        require(actor.level == AgentLevel.NPC)
    }
}

/** The facts a drone operator's strike reads (#259), observed only for operators. */
object DroneFacts {
    /** His drone is up. */
    val AIRBORNE = FactKey("drone.airborne", Boolean::class.javaObjectType)

    /** The target the drone in the air still has, observed only while it has one. */
    val FLYING = FactKey("drone.flying", StrikePoint::class.java)
    val HOLD = FactKey("drone.hold", DroneHold::class.java)
    val OPTIONS = FactKey("drone.options", StrikeOptions::class.java)
}

/**
 * A drone operator's strike (#259): a family of `npc.execute` that takes him over ahead of his
 * duty while his drone is up or one of its strike methods applies, and the root of a squadless
 * operator's plan.
 *
 * The methods are tried in [ORDER], first wins: a drone in the air keeps its target, else a
 * target is taken by source (the squad's focus, its objective, his team's enemy, one he or the
 * drone saw, one his side reported), else a drone in the air flies home, and `stand_by` is the
 * last resort, his ordinary fight. A new target for a drone in the air is the same choice, made
 * again when the old one is lost.
 */
object DroneStrike {
    val TASK = TaskKey("npc.strike", StrikeCall::class.java)

    /** A squadless operator's root: his strike, his engage, or nothing. */
    val ALONE = TaskKey("npc.strike_alone", StrikeCall::class.java)

    /** The drone's flight, carried out by the strike executor. */
    val ACTION = ActionKey("npc.drone", StrikeDuty::class.java)
    private val FLIGHT = TaskKey("npc.drone", StrikeDuty::class.java)

    /** The methods of `npc.strike`, first wins. */
    enum class Plan(val source: StrikeSource? = null) {
        KEEP_FLYING,
        STRIKE_FOCUS(StrikeSource.FOCUS),
        STRIKE_OBJECTIVE(StrikeSource.OBJECTIVE),
        STRIKE_TEAM(StrikeSource.TEAM),
        STRIKE_SPOTTED(StrikeSource.SPOTTED),
        STRIKE_REPORTED(StrikeSource.REPORTED),
        FLY_HOME,
        STAND_BY,
    }

    val ORDER = Plan.entries

    private val FactView.airborne get() = value(DroneFacts.AIRBORNE) == true

    /** Whether [plan] would apply on [facts], ignoring the plans before it. */
    fun wants(plan: Plan, facts: FactView): Verdict {
        val airborne = facts.value(DroneFacts.AIRBORNE) ?: return Verdict.Unknown(setOf(DroneFacts.AIRBORNE.name))
        val source = plan.source
        return when {
            plan == Plan.KEEP_FLYING -> allow(airborne && facts.value(DroneFacts.FLYING) != null, "no_flight")
            source != null -> strike(source, airborne, facts)
            plan == Plan.FLY_HOME -> allow(airborne, "grounded")
            else -> Verdict.Allowed
        }
    }

    private fun strike(source: StrikeSource, airborne: Boolean, facts: FactView): Verdict {
        if (!airborne) {
            val hold = facts.value(DroneFacts.HOLD) ?: return Verdict.Unknown(setOf(DroneFacts.HOLD.name))
            if (hold != DroneHold.NONE) return Verdict.Rejected(hold.name.lowercase())
        }
        val options = facts.value(DroneFacts.OPTIONS) ?: return Verdict.Unknown(setOf(DroneFacts.OPTIONS.name))
        return allow(source in options.bySource, "no_${source.name.lowercase()}_target")
    }

    private fun allow(allowed: Boolean, reason: String): Verdict = if (allowed) Verdict.Allowed else Verdict.Rejected(reason)

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

    /** The plan that applies on [facts], or null while a fact it needs is unknown. */
    fun choose(facts: FactView): Plan? = ORDER.firstOrNull { applies(it).evaluate(facts) == Verdict.Allowed }

    /**
     * The `drone_strike` method of `npc.execute`: his drone is up, or he would launch one. Its
     * rejection says why not, the reason the decision trace shows for a drone kept in its case;
     * a man without [DroneFacts.AIRBORNE] observed is no operator.
     */
    fun takesOver() =
        Condition { facts ->
            if (facts.value(DroneFacts.AIRBORNE) == null) return@Condition Verdict.Rejected("no_drone")
            when (choose(facts)) {
                null -> Verdict.Unknown(setOf(DroneFacts.AIRBORNE.name, DroneFacts.HOLD.name, DroneFacts.OPTIONS.name))
                Plan.STAND_BY -> Verdict.Rejected(facts.value(DroneFacts.HOLD)?.takeIf { it != DroneHold.NONE }?.name?.lowercase() ?: "no_strike_target")
                else -> Verdict.Allowed
            }
        }

    /**
     * Why another family of `npc.execute` stands aside: his drone is up. Fire, contact and the hole
     * do not take a flying operator's feet; a cancel would lose the drone's signal, and today's
     * operator stands still at the monitor under fire.
     */
    fun flying(facts: FactView): Verdict? = if (facts.airborne) Verdict.Rejected("flying_drone") else null

    /** Why a duty method stands aside: his drone is up, or he launches one (see [takesOver]). */
    fun standsAside(facts: FactView): Verdict? = flying(facts) ?: if (takesOver().evaluate(facts) == Verdict.Allowed) Verdict.Rejected("drone_strike") else null

    /** [plan]'s duty for [call]: the target of its source, the one in flight, or home. */
    fun duty(plan: Plan, call: StrikeCall, facts: FactView): StrikeDuty {
        val target = when (plan) {
            Plan.KEEP_FLYING -> facts.value(DroneFacts.FLYING)
            Plan.FLY_HOME, Plan.STAND_BY -> null
            else -> facts.value(DroneFacts.OPTIONS)?.bySource?.get(plan.source)
        }
        return StrikeDuty(call.actor, target, plan.source)
    }

    /** The strike's tasks, joined into the NPC domain; [engage] is a squadless man's fight. */
    fun tasks(engage: (StrikeCall) -> TaskNetwork): List<TaskDefinition<*>> =
        listOf(
            CompoundTask(
                TASK,
                ORDER.map { plan ->
                    Method<StrikeCall>(plan.name.lowercase(), eligible = { applies(plan) }, decompose = { call, facts ->
                        if (plan == Plan.STAND_BY) TaskNetwork.EMPTY else TaskNetwork.sequence(FLIGHT.call(duty(plan, call, facts)))
                    })
                },
            ),
            // A squadless operator has no duty to fall back on: his engage in a fight, else nothing.
            CompoundTask(
                ALONE,
                listOf(
                    Method<StrikeCall>("drone_strike", eligible = { takesOver() }, decompose = { call, _ -> TaskNetwork.sequence(TASK.call(call)) }),
                    Method<StrikeCall>("engage", eligible = {
                        Condition { facts -> standsAside(facts) ?: Condition.test(TacticalFacts.IN_CONTACT, "no_contact") { it }.evaluate(facts) }
                    }, decompose = { call, _ -> engage(call) }),
                    Method<StrikeCall>("stand_by", eligible = {
                        Condition { facts -> standsAside(facts) ?: if (facts.value(TacticalFacts.IN_CONTACT) == true) Verdict.Rejected("engages") else Verdict.Allowed }
                    }, decompose = { _, _ -> TaskNetwork.EMPTY }),
                ),
            ),
            // One identity per man: a new target or the way home revises the flight in place, as a
            // restart would lose the drone's signal and blow it up where it is.
            PrimitiveTask(FLIGHT) { duty ->
                PrimitiveSpec(
                    "drone:${duty.actor.id}",
                    ActionSpec(ACTION, duty, setOf(ResourceKey(duty.actor, "motion")), timeoutTicks = Long.MAX_VALUE),
                    TaskContract(),
                )
            },
        )
}
