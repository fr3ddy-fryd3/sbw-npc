package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.ActionKey
import com.sbwnpc.squad.ai.planning.ActionSpec
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.FactKey
import com.sbwnpc.squad.ai.planning.Method
import com.sbwnpc.squad.ai.planning.PrimitiveSpec
import com.sbwnpc.squad.ai.planning.PrimitiveTask
import com.sbwnpc.squad.ai.planning.ResourceKey
import com.sbwnpc.squad.ai.planning.TaskContract
import com.sbwnpc.squad.ai.planning.TaskKey
import com.sbwnpc.squad.ai.planning.TaskNetwork
import com.sbwnpc.squad.ai.planning.UtilityScore
import com.sbwnpc.squad.ai.planning.Verdict

/** A man's own care facts, observed by the integration (#189). */
object SelfCareFacts {
    /** He is wounded, has a medkit, and is (or just was) in cover, his hole or at a patient. */
    val DUE = FactKey("actor.self_treat_due", Boolean::class.javaObjectType)
}

/** [actor] dresses his own wound with one of his medkits. */
data class SelfTreatment(val actor: AgentId)

/**
 * A wounded man's own dressing as a method of every NPC root (#189, owner 11.11): the dressing is
 * instant, but spending the medkit is his plan's decision. The fact carries the gates the old
 * behaviour had; the integration's executor dresses.
 */
object SelfCare {
    /** The dressing, carried out by the integration's executor. */
    val ACTION = ActionKey("npc.treat_self", SelfTreatment::class.java)
    private val TASK = TaskKey("npc.treat_self", SelfTreatment::class.java)

    /**
     * Above [MedicalCare.PRIORITY]: a medic at his patient dresses himself first, as the mandatory
     * rules ranked it (#257). A live grenade or a drone forbids it through the fact instead.
     */
    val PRIORITY = UtilityScore.product(PRIORITY_BASE, emptyList(), "self_treatment")

    /** The method joined into each NPC root. */
    fun <P : Any> method(actor: (P) -> AgentId): Method<P> =
        Method(
            "treat_self",
            // An unobserved fact is no wound: every root but a man's own has no such fact.
            eligible = { _ -> Condition { facts -> if (facts.value(SelfCareFacts.DUE) == true) Verdict.Allowed else Verdict.Rejected("not_due") } },
            score = { _, _ -> PRIORITY },
            decompose = { call, _ -> TaskNetwork.sequence(TASK.call(SelfTreatment(actor(call)))) },
        )

    /** The dressing's primitive, joined into the NPC domain. */
    fun task() =
        PrimitiveTask(TASK) { call ->
            PrimitiveSpec(
                "treat_self:${call.actor.id}",
                ActionSpec(ACTION, call, setOf(ResourceKey(call.actor, "motion")), timeoutTicks = TIMEOUT_TICKS),
                TaskContract(),
            )
        }

    /** The dressing ends in its first tick; a stuck one is given up and replanned. */
    private const val TIMEOUT_TICKS = 40L
    private const val PRIORITY_BASE = 0.65
}
