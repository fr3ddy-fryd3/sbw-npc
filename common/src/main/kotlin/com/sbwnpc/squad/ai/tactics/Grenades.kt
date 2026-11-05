package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.ActionKey
import com.sbwnpc.squad.ai.planning.ActionSpec
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.FactKey
import com.sbwnpc.squad.ai.planning.FactView
import com.sbwnpc.squad.ai.planning.PrimitiveSpec
import com.sbwnpc.squad.ai.planning.PrimitiveTask
import com.sbwnpc.squad.ai.planning.ResourceKey
import com.sbwnpc.squad.ai.planning.TaskContract
import com.sbwnpc.squad.ai.planning.TaskKey
import com.sbwnpc.squad.ai.planning.Verdict

/** Why a man would throw a grenade now, or [NONE]. */
enum class GrenadeReason {
    NONE,

    /** Three or more enemies bunched together, one of them in sight. */
    BUNCH,

    /** His target has been out of his sight for a while, where his squad last saw it. */
    FLUSH,

    /** He is pinned by fire from a place he knows, not from the air. */
    SUPPRESSION,

    /** A grenadier's hand throw at the target he sees, beside his launcher. */
    GRENADIER,
}

/** A man's grenade facts, observed by the integration's grenade choice. */
object GrenadeFacts {
    /** Why he would throw now, his squad's ration allowing: [GrenadeReason.NONE] for no throw. */
    val DUE = FactKey("grenade.due", GrenadeReason::class.java)

    /** Grenades he has thrown: each throw is a new action, not the last one carried on. */
    val THROWN = FactKey("grenade.thrown", Int::class.javaObjectType)
}

/** The [number]th grenade [actor] throws. */
data class GrenadeThrow(val actor: AgentId, val number: Int)

/**
 * A grenade as one of the NPC domain's ways to reach an enemy in cover or in a bunch (#263): a
 * method of `npc.execute`, and of an engage-only man's fight, tried before his engage and his duty,
 * so a throw comes before closing in and before holding a spot to fire (owner, 2.11). The NPC level
 * chooses by conditions and order, not utility (#172).
 *
 * The throw conditions and the squad's ration are the observation's ([GrenadeFacts.DUE]): the
 * first man whose plan throws spends the ration, and the others' facts go back.
 */
object Grenades {
    /** The throw, carried out by the integration's executor. */
    val ACTION = ActionKey("npc.grenade", GrenadeThrow::class.java)
    private val THROW = TaskKey("npc.grenade", GrenadeThrow::class.java)

    /**
     * A throw takes a few ticks at most; one that has not happened by then (no arc reached the
     * point) is given up, and the next observation decides again.
     */
    const val THROW_TIMEOUT = 40L

    /**
     * Whether he throws now: his squad lets him, and nothing he must answer first is going on. A
     * man under fire or in his hole answers that first, unless the grenade is for the fire itself:
     * cut off mid-move, his cover search started over and over (plan review, #263).
     * [underFire] is the under-fire families' own verdict for him.
     */
    fun throws(facts: FactView, underFire: Verdict): Verdict {
        // A man nobody observes for grenades (a plan built without them) has none to throw.
        val reason = facts.value(GrenadeFacts.DUE) ?: return Verdict.Rejected("no_grenade_due")
        return when {
            reason == GrenadeReason.NONE -> Verdict.Rejected("no_grenade_due")
            reason == GrenadeReason.SUPPRESSION -> Verdict.Allowed
            underFire is Verdict.Unknown -> underFire
            underFire == Verdict.Allowed -> Verdict.Rejected("under_fire_first")
            else -> Verdict.Allowed
        }
    }

    /** The throw [actor] makes next. */
    fun next(actor: AgentId, facts: FactView) = THROW.call(GrenadeThrow(actor, facts.value(GrenadeFacts.THROWN) ?: 0))

    /** The throw's primitive, joined into the NPC domain. */
    fun task() =
        PrimitiveTask(THROW) { call ->
            PrimitiveSpec(
                "grenade:${call.actor.id}:${call.number}",
                ActionSpec(ACTION, call, setOf(ResourceKey(call.actor, "motion")), timeoutTicks = THROW_TIMEOUT),
                // A throw needs his feet for a moment, not contact or an engage: a pinned man in
                // cover throws at the fire as before. Its executor ends it, thrown or not.
                TaskContract(),
            )
        }
}
