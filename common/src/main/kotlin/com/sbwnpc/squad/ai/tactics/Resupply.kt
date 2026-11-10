package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.ActionKey
import com.sbwnpc.squad.ai.planning.ActionSpec
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.FactKey
import com.sbwnpc.squad.ai.planning.FactView
import com.sbwnpc.squad.ai.planning.PrimitiveSpec
import com.sbwnpc.squad.ai.planning.PrimitiveTask
import com.sbwnpc.squad.ai.planning.ResourceKey
import com.sbwnpc.squad.ai.planning.TaskContract
import com.sbwnpc.squad.ai.planning.TaskKey
import com.sbwnpc.squad.ai.planning.TaskNetwork
import com.sbwnpc.squad.ai.planning.UtilityScore
import com.sbwnpc.squad.ai.planning.Verdict
import java.util.UUID

/** A man's ammunition facts, observed by the integration's trip allocation (#189). */
object SupplyFacts {
    /** The Supply the allocation sent him to, absent while he has no trip. */
    val TRIP = FactKey("supply.trip", Point3::class.java)

    /** His gun in hand is empty while he has a live target: he cannot fight. */
    val EMPTY = FactKey("supply.empty", Boolean::class.javaObjectType)
}

/** [actor] goes to the Supply at [supply] and stands in its reach until topped up. */
data class SupplyTrip(val actor: AgentId, val supply: Point3)

/** [actor], his gun empty and no trip, gets out of his enemy's way. */
data class AmmoWithdrawal(val actor: AgentId)

/** One man of a squad who could go to resupply. */
data class SupplyCandidate(val id: UUID, val empty: Boolean, val fraction: Double, val distance: Double)

/**
 * Who of a squad goes to resupply (#189). The allocation decides, the walk and the wait are
 * execution. At most half the squad's living men are away at once (owner 09.11), at least one: a
 * whole squad used to leave its line together when its rounds ran low at the same time.
 *
 * Who needs a trip is today's rule: out of rounds in a fight, low with nobody to fight, or all but
 * out while firing (the fire task walks him then, shooting).
 */
object Resupply {
    val TRIP_ACTION = ActionKey("npc.resupply", SupplyTrip::class.java)
    val WITHDRAW_ACTION = ActionKey("npc.ammo_withdraw", AmmoWithdrawal::class.java)
    private val TRIP_TASK = TaskKey("npc.resupply", SupplyTrip::class.java)
    private val WITHDRAW_TASK = TaskKey("npc.ammo_withdraw", AmmoWithdrawal::class.java)

    /** How many of a squad's [living] men may be away resupplying at once. */
    fun cap(living: Int) = maxOf(1, living / 2)

    /**
     * The men of one squad on a trip after this pass: those of [current] still among [candidates]
     * keep theirs, as each hand-over replanned both men; free places go to the empty first, then the
     * lowest, then the nearest. [returning] men, topped up and still on their way back, hold their
     * places too (owner 10.11): the line keeps its half. [squadless] men are on their own: no cap.
     */
    fun pick(current: Set<UUID>, candidates: List<SupplyCandidate>, living: Int, squadless: Boolean = false, returning: Int = 0): Set<UUID> {
        val kept = candidates.filter { it.id in current }.mapTo(LinkedHashSet()) { it.id }
        val room = if (squadless) candidates.size else cap(living) - returning
        val order = compareBy<SupplyCandidate>({ !it.empty }, { it.fraction }, { it.distance })
        candidates.filter { it.id !in kept }.sortedWith(order).forEach { if (kept.size < room) kept += it.id }
        return kept
    }

    /**
     * Both methods' score: above the neutral one of a man's duties, below a medic's patient
     * (MedicalCare.PRIORITY). Under fire, in his hole, under a roof or at his drone he answers that
     * first: both stand aside then, as the old behaviours yielded to cover.
     */
    val PRIORITY = UtilityScore.product(PRIORITY_BASE, emptyList(), "ammunition")

    /** The man has a trip and may take it now: no target, or a gun with nothing left to fire. */
    fun goes(intent: PositionIntent?) =
        Condition { facts ->
            standsAside(intent, facts)?.let { return@Condition it }
            when {
                facts.value(SupplyFacts.TRIP) == null -> Verdict.Rejected("no_trip")
                facts.value(SupplyFacts.EMPTY) != true && facts.value(TacticalFacts.IN_CONTACT) == true -> Verdict.Rejected("fights_on")
                else -> Verdict.Allowed
            }
        }

    /** Empty in a fight with no trip (no Supply, or the squad's half already away): he withdraws. */
    fun withdraws(intent: PositionIntent?) =
        Condition { facts ->
            standsAside(intent, facts)?.let { return@Condition it }
            if (facts.value(SupplyFacts.EMPTY) == true && facts.value(SupplyFacts.TRIP) == null) Verdict.Allowed else Verdict.Rejected("not_empty")
        }

    // An engage-only or squadless root has no position intent: only the drone keeps him then.
    private fun standsAside(intent: PositionIntent?, facts: FactView): Verdict? = DroneStrike.flying(facts) ?: intent?.let { UnderFire.duty(it, facts) }

    fun trip(actor: AgentId, facts: FactView) = TaskNetwork.sequence(TRIP_TASK.call(SupplyTrip(actor, facts.value(SupplyFacts.TRIP)!!)))

    fun withdraw(actor: AgentId) = TaskNetwork.sequence(WITHDRAW_TASK.call(AmmoWithdrawal(actor)))

    /** The trip's primitive, joined into the NPC domain. */
    fun tripTask() =
        PrimitiveTask(TRIP_TASK) { call ->
            PrimitiveSpec("resupply:${call.actor.id}:${call.supply}", ActionSpec(TRIP_ACTION, call, setOf(ResourceKey(call.actor, "motion")), timeoutTicks = TIMEOUT_TICKS), TaskContract())
        }

    /** The withdrawal's primitive, joined into the NPC domain. */
    fun withdrawTask() =
        PrimitiveTask(WITHDRAW_TASK) { call ->
            PrimitiveSpec("ammo_withdraw:${call.actor.id}", ActionSpec(WITHDRAW_ACTION, call, setOf(ResourceKey(call.actor, "motion")), timeoutTicks = TIMEOUT_TICKS), TaskContract())
        }

    /** A trip or withdrawal still running after five minutes is given up; the allocation decides again. */
    private const val TIMEOUT_TICKS = 6000L
    private const val PRIORITY_BASE = 0.55
}
