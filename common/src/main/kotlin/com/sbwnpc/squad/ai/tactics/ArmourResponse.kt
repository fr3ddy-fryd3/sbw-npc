package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import java.util.UUID

/** A known ground-armour source; positions refresh separately from the stable planning identity. */
data class ArmourContact(val id: UUID, val sector: FireSector)

/** The physical progress of the manoeuvre, never an alternative decision controller. */
enum class ArmourStep { COVER, SORTIE, FIRE, RETURN, ESCAPE, HOLD }

/** One execution unit chosen by the NPC domain, against [contact]. */
data class ArmourUnit(val actor: AgentId, val contact: ArmourContact, val step: ArmourStep, val launcher: Boolean, val cycle: Int)

/** Facts for an armour response, independent of bullet suppression. */
object ArmourFacts {
    val CONTACT = FactKey("armour.contact", ArmourContact::class.java)
    val LOADED = FactKey("armour.loaded", Boolean::class.javaObjectType)
    val RUNNING = FactKey("armour.running", ArmourStep::class.java)
    val STEP = FactKey("armour.step", ArmourStep::class.java)
    val CYCLE = FactKey("armour.cycle", Int::class.javaObjectType)
    val COVER_FAILED = FactKey("armour.cover_failed", Boolean::class.javaObjectType)
    val ESCAPE_FAILED = FactKey("armour.escape_failed", Boolean::class.javaObjectType)
    val SORTIE_READY = FactKey("armour.sortie_ready", Boolean::class.javaObjectType)
    val SORTIE_LAUNCHER = FactKey("armour.sortie_launcher", Boolean::class.javaObjectType)
    val HIDDEN = FactKey("armour.hidden", Boolean::class.javaObjectType)
    val SHELTERED = FactKey("armour.sheltered", Boolean::class.javaObjectType)
}

/** The NPC domain's answer to armour (#237), with an explicit last-resort hold. */
object ArmourResponse {
    val TASK = TaskKey("npc.answer_armour", PositionIntent::class.java)
    val ACTION = ActionKey("npc.armour_unit", ArmourUnit::class.java)
    private val UNIT = TaskKey("npc.armour_unit", ArmourUnit::class.java)
    const val MEMORY_TICKS = 400L

    /** A flying drone and an existing foxhole keep their contracts; otherwise armour takes precedence. */
    fun active(facts: FactView) = facts.value(ArmourFacts.CONTACT) != null && (facts.value(TacticalFacts.DIGS) ?: 0) == 0 && facts.value(DroneFacts.AIRBORNE) != true

    /** FOLLOW's moving half keeps its agreed bound after the player (#116). */
    fun answers(intent: PositionIntent, facts: FactView) = !intent.ordered && active(facts)

    fun standsAside(intent: PositionIntent, facts: FactView): Verdict? = if (answers(intent, facts)) Verdict.Rejected("answer_armour") else null

    /** A sheltered grounded operator may launch, except while ordinary fire keeps him down. */
    fun launches(facts: FactView) = active(facts) && facts.value(ArmourFacts.HIDDEN) == true && facts.value(TacticalFacts.UNDER_FIRE) != true && DroneStrike.takesOver().evaluate(facts) == Verdict.Allowed

    /** Which action is allowed next: every combination has one answer, including exhausted searches. */
    fun choose(facts: FactView): ArmourStep {
        val running = facts.value(ArmourFacts.RUNNING)
        val loadedNow = facts.value(ArmourFacts.LOADED) == true
        if (running != null) return runningStep(running, loadedNow, facts)
        val last = facts.value(ArmourFacts.STEP)
        val loaded = facts.value(ArmourFacts.LOADED) == true
        if (last == ArmourStep.SORTIE) return afterSortie(loaded, facts)
        if (last == ArmourStep.FIRE) return ArmourStep.RETURN
        if (facts.value(ArmourFacts.SHELTERED) == true) return if (last == ArmourStep.RETURN) ArmourStep.HOLD else ArmourStep.SORTIE
        if (facts.value(ArmourFacts.COVER_FAILED) != true) return ArmourStep.COVER
        if (loaded) return ArmourStep.SORTIE
        return if (facts.value(ArmourFacts.ESCAPE_FAILED) == true) ArmourStep.HOLD else ArmourStep.ESCAPE
    }

    private fun runningStep(running: ArmourStep, loaded: Boolean, facts: FactView): ArmourStep {
        val onSortie = running == ArmourStep.SORTIE || running == ArmourStep.FIRE
        if (onSortie && facts.value(ArmourFacts.SORTIE_LAUNCHER) == true && !loaded) return ArmourStep.RETURN
        if (running == ArmourStep.FIRE && !loaded && facts.value(ArmourFacts.HIDDEN) != true) return ArmourStep.RETURN
        return running
    }

    private fun afterSortie(loaded: Boolean, facts: FactView): ArmourStep {
        if (facts.value(ArmourFacts.SORTIE_LAUNCHER) == true && !loaded) return ArmourStep.RETURN
        val ready = facts.value(ArmourFacts.SORTIE_READY) == true
        val safe = loaded || facts.value(ArmourFacts.HIDDEN) == true
        return if (ready && safe) ArmourStep.FIRE else ArmourStep.RETURN
    }

    private const val FORWARD_TOLERANCE = 0.01
    private const val UNIT_TIMEOUT_TICKS = 400L

    /** A sortie cannot advance along the cover-to-armour bearing, even after terrain projection. */
    fun lateralOrBack(origin: Point3, threat: Point3, point: Point3): Boolean = (point.x - origin.x) * (threat.x - origin.x) + (point.z - origin.z) * (threat.z - origin.z) <= FORWARD_TOLERANCE

    fun tasks(): List<TaskDefinition<*>> =
        listOf(
            CompoundTask(
                TASK,
                ArmourStep.entries.map { step ->
                    Method<PositionIntent>(step.name.lowercase(), eligible = { Condition { facts -> if (choose(facts) == step) Verdict.Allowed else Verdict.Rejected("another_armour_step") } }, decompose = { intent, facts ->
                        val unit = ArmourUnit(intent.actor, facts.value(ArmourFacts.CONTACT)!!, step, facts.value(ArmourFacts.LOADED) == true, facts.value(ArmourFacts.CYCLE) ?: 0)
                        TaskNetwork.sequence(UNIT.call(unit))
                    })
                },
            ),
            PrimitiveTask(UNIT) { unit ->
                PrimitiveSpec("armour:${unit.contact.id}:${unit.step}:${unit.cycle}:${unit.launcher}", ActionSpec(ACTION, unit, setOf(ResourceKey(unit.actor, "motion")), timeoutTicks = UNIT_TIMEOUT_TICKS), TaskContract(maintain = Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it }, completed = Condition.test(ArmourFacts.CYCLE, "armour_unit_running") { it > unit.cycle }), planning = Condition { facts -> if (facts.owner == unit.actor) Verdict.Allowed else Verdict.Rejected("foreign_actor_intent") })
            },
        )
}
