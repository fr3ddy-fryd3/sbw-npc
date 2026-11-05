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

/** An accepted physical outing; its identity survives changes of exposure while walking. */
data class ArmourCommitment(val contact: ArmourContact, val baseCycle: Int)

/** Facts for an armour response, independent of bullet suppression. */
object ArmourFacts {
    val CONTACT = FactKey("armour.contact", ArmourContact::class.java)
    val LOADED = FactKey("armour.loaded", Boolean::class.javaObjectType)
    val COMMITMENT = FactKey("armour.commitment", ArmourCommitment::class.java)
    val RETURN_DUE = FactKey("armour.return_due", Boolean::class.javaObjectType)
    val SORTIE_ALLOWED = FactKey("armour.sortie_allowed", Boolean::class.javaObjectType)
    val CYCLE = FactKey("armour.cycle", Int::class.javaObjectType)
    val COVER_FAILED = FactKey("armour.cover_failed", Boolean::class.javaObjectType)
    val ESCAPE_FAILED = FactKey("armour.escape_failed", Boolean::class.javaObjectType)
    val SORTIE_READY = FactKey("armour.sortie_ready", Boolean::class.javaObjectType)
    val SORTIE_LAUNCHER = FactKey("armour.sortie_launcher", Boolean::class.javaObjectType)
    val EXPOSED = FactKey("armour.exposed", Boolean::class.javaObjectType)
    val RELEVANT = FactKey("armour.relevant", ArmourContact::class.java)
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
    fun answers(intent: PositionIntent, facts: FactView) =
        !intent.ordered && active(facts) && facts.value(TacticalFacts.UNDER_FIRE) != true &&
            (
                facts.value(ArmourFacts.COMMITMENT) != null || facts.value(ArmourFacts.RETURN_DUE) == true || facts.value(ArmourFacts.EXPOSED) == true ||
                    (facts.value(ArmourFacts.LOADED) == true && facts.value(ArmourFacts.SORTIE_ALLOWED) == true)
                )

    fun standsAside(intent: PositionIntent, facts: FactView): Verdict? = if (answers(intent, facts)) Verdict.Rejected("answer_armour") else null

    /** A sheltered grounded operator may launch, except while ordinary fire keeps him down. */
    fun launches(facts: FactView) = active(facts) && facts.value(ArmourFacts.HIDDEN) == true && facts.value(TacticalFacts.UNDER_FIRE) != true && DroneStrike.takesOver().evaluate(facts) == Verdict.Allowed

    private const val FORWARD_TOLERANCE = 0.01
    private const val UNIT_TIMEOUT_TICKS = 400L

    /** A sortie cannot advance along the cover-to-armour bearing, even after terrain projection. */
    fun lateralOrBack(origin: Point3, threat: Point3, point: Point3): Boolean = (point.x - origin.x) * (threat.x - origin.x) + (point.z - origin.z) * (threat.z - origin.z) <= FORWARD_TOLERANCE

    fun tasks(): List<TaskDefinition<*>> =
        listOf(
            CompoundTask(
                TASK,
                listOf(
                    method("return_to_shelter", ::returning, ArmourStep.RETURN),
                    method("launcher_sortie", ::launcherSortie, ArmourStep.SORTIE, ArmourStep.FIRE, ArmourStep.RETURN),
                    method("take_cover", ::needsCover, ArmourStep.COVER),
                    method("escape_armour", ::needsEscape, ArmourStep.ESCAPE),
                    method("hold_as_ordered", ::holds, ArmourStep.HOLD),
                ),
            ),
            PrimitiveTask(UNIT) { unit ->
                PrimitiveSpec("armour:${unit.contact.id}:${unit.step}:${unit.cycle}:${unit.launcher}", ActionSpec(ACTION, unit, setOf(ResourceKey(unit.actor, "motion")), timeoutTicks = UNIT_TIMEOUT_TICKS), TaskContract(maintain = Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it }, completed = Condition.test(ArmourFacts.CYCLE, "armour_unit_running") { it > unit.cycle }), planning = Condition { facts -> if (facts.owner == unit.actor) Verdict.Allowed else Verdict.Rejected("foreign_actor_intent") })
            },
        )

    private fun method(name: String, eligible: (FactView) -> Boolean, vararg steps: ArmourStep) = Method<PositionIntent>(name, eligible = { Condition { facts -> if (eligible(facts)) Verdict.Allowed else Verdict.Rejected("${name}_not_needed") } }, decompose = { intent, facts -> units(intent, facts, steps.toList()) })

    /** A real dependency sequence: each unit confirms its own physical completion. */
    private fun units(intent: PositionIntent, facts: FactView, steps: List<ArmourStep>): TaskNetwork {
        val contact = facts.value(ArmourFacts.CONTACT)!!
        val commitment = facts.value(ArmourFacts.COMMITMENT)
        val cycle = commitment?.baseCycle ?: (facts.value(ArmourFacts.CYCLE) ?: 0)
        return TaskNetwork(steps.mapIndexed { index, step -> NetworkNode(index.toString(), UNIT.call(ArmourUnit(intent.actor, contact, step, commitment != null || facts.value(ArmourFacts.LOADED) == true, cycle + (if (commitment != null && steps == listOf(ArmourStep.RETURN)) 2 else index))), if (index == 0) emptySet() else setOf((index - 1).toString())) })
    }
}

private fun returning(f: FactView) = f.value(ArmourFacts.RETURN_DUE) == true || (f.value(ArmourFacts.COMMITMENT) != null && f.value(ArmourFacts.LOADED) != true)
private fun launcherReady(f: FactView) = f.value(ArmourFacts.LOADED) == true && f.value(ArmourFacts.SORTIE_ALLOWED) == true
private fun launcherSortie(f: FactView) = !returning(f) && (f.value(ArmourFacts.COMMITMENT) != null || newSortie(f))
private fun newSortie(f: FactView) = launcherReady(f) && (f.value(ArmourFacts.HIDDEN) == true || f.value(ArmourFacts.COVER_FAILED) == true)
private fun needsCover(f: FactView) = !returning(f) && f.value(ArmourFacts.COMMITMENT) == null && f.value(ArmourFacts.EXPOSED) == true && f.value(ArmourFacts.COVER_FAILED) != true
private fun needsEscape(f: FactView) = !returning(f) && f.value(ArmourFacts.COMMITMENT) == null && exposedWithoutCover(f) && f.value(ArmourFacts.ESCAPE_FAILED) != true && !launcherReady(f)
private fun exposedWithoutCover(f: FactView) = f.value(ArmourFacts.EXPOSED) == true && f.value(ArmourFacts.COVER_FAILED) == true
private fun holds(f: FactView) = !returning(f) && f.value(ArmourFacts.COMMITMENT) == null && !launcherReady(f) && (f.value(ArmourFacts.HIDDEN) == true || f.value(ArmourFacts.ESCAPE_FAILED) == true)
