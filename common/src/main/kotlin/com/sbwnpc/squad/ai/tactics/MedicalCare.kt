package com.sbwnpc.squad.ai.tactics

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

/** A medic's care facts, observed by the integration's patient allocation (#189). */
object MedicalFacts {
    /** The patient the allocation gave him, absent when he has none. */
    val PATIENT = FactKey("medic.patient", UUID::class.java)
}

/** [actor] treats [patient] until the patient no longer needs him. */
data class Treatment(val actor: AgentId, val patient: UUID)

/** A wounded ally a medic could treat: [fraction] of his health left, [distance] blocks from the medic. */
data class Wounded(val id: UUID, val fraction: Float, val distance: Double)

/**
 * Who of a side's wounded each medic treats (#189, owner 08.11: the plan decides who heals, the
 * dressing stays execution). The allocation picks; the NPC domain's `treat` method carries the pick
 * out ahead of the man's duty and fight, as medical help came first before; the integration's
 * executor walks and dresses.
 *
 * Today's thresholds are kept: a critical ally (below [CRITICAL]) always, a wounded one (below
 * [WOUNDED]) only while the medic has no target of his own.
 */
object MedicalCare {
    /** The treatment, carried out by the integration's executor. */
    val ACTION = ActionKey("npc.treat", Treatment::class.java)
    private val TREAT = TaskKey("npc.treat", Treatment::class.java)

    const val CRITICAL = 0.3f
    const val WOUNDED = 0.7f

    /** Whether an ally at [fraction] of his health needs a medic who is [fighting] or not. */
    fun needs(fraction: Float, fighting: Boolean) = fraction < CRITICAL || (!fighting && fraction < WOUNDED)

    /**
     * The patient a medic takes: the one he has while it still needs him, else critical first,
     * then the most hurt, then the nearest. [taken] leaves out patients another medic holds, so two
     * medics never walk to one man; a patient is never handed from one medic to another, as each
     * hand-over replanned both.
     */
    fun pick(current: UUID?, candidates: List<Wounded>, fighting: Boolean, taken: (UUID) -> Boolean): UUID? {
        val open = candidates.filter { needs(it.fraction, fighting) && !taken(it.id) }
        if (current != null && open.any { it.id == current }) return current
        return open.minWithOrNull(compareBy<Wounded>({ it.fraction >= CRITICAL }, { it.fraction }, { it.distance }))?.id
    }

    /**
     * The `treat` method's score, above the neutral one every other method of a man's roots holds:
     * methods of equal score are tried by name, and `answer_armour`, `engage` or `in_hole` came before
     * `treat`. A medic dug in, in cover or riding is given no patient at all, as before.
     */
    val PRIORITY = UtilityScore.product(PRIORITY_BASE, emptyList(), "medical_help")

    /** Whether the man has a patient to treat now. */
    val treats = Condition { facts -> if (facts.value(MedicalFacts.PATIENT) != null) Verdict.Allowed else Verdict.Rejected("no_patient") }

    /** His treatment of the patient his facts name. */
    fun treat(actor: AgentId, facts: FactView) = TaskNetwork.sequence(TREAT.call(Treatment(actor, facts.value(MedicalFacts.PATIENT)!!)))

    /** The treatment's primitive, joined into the NPC domain. */
    fun task() =
        PrimitiveTask(TREAT) { call ->
            PrimitiveSpec(
                "treat:${call.actor.id}:${call.patient}",
                // Repeated dressings of one patient are one task: a new identity each would replan him.
                ActionSpec(ACTION, call, setOf(ResourceKey(call.actor, "motion")), timeoutTicks = TIMEOUT_TICKS),
                TaskContract(),
            )
        }

    /** A treatment that has not ended in a minute is given up, and the next allocation decides again. */
    private const val TIMEOUT_TICKS = 1200L
    private const val PRIORITY_BASE = 0.6
}
