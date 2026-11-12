package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.FactKey
import com.sbwnpc.squad.ai.planning.FactView
import com.sbwnpc.squad.ai.planning.TaskCall
import com.sbwnpc.squad.ai.planning.TaskNetwork
import com.sbwnpc.squad.ai.planning.Verdict

/**
 * How a man in a fight moves while nothing else commands his movement (#251 step B). [endsOn] is
 * the fact the move runs for, so its unit ends when the fact lets go rather than on a threshold of
 * its executor's own; null for a move only its executor ends.
 */
enum class EngageMove(val endsOn: FactKey<Boolean>? = null) {
    /** A downed pilot backs off from an enemy too near. */
    KEEP_AWAY,

    /** A defender outside his area walks back into it, firing on the way. */
    RETURN_TO_AREA(TacticalFacts.OUTSIDE_AREA),

    /** An ally stands in his line of fire: he steps aside. */
    SIDESTEP(TacticalFacts.LANE_BLOCKED_ALLY),

    /** A vehicle hull stands in his line of fire: he looks for another spot; a sidestep cannot clear a hull. */
    RESEARCH_SPOT(TacticalFacts.LANE_BLOCKED_HULL),

    /** A target he can newly shoot gets an opening burst before he moves. */
    OPEN_FIRE(TacticalFacts.OPENING_DUE),

    /**
     * His target vanished into foliage: two short bursts where he last saw it, standing (#189).
     * A guess at a target is the plan's to make; only the reply to fire he takes is a rule.
     */
    FOLIAGE_FIRE(TacticalFacts.FOLIAGE_FIRE_DUE),

    /** Holding a post, he makes one bounded approach towards a far enemy. */
    ADVANCE_FROM_POST,

    /** He closes on an enemy he can shoot by bounds: a rush, then a pause to fire. */
    CLOSE_IN,

    /** He cannot shoot the enemy from here: he walks on to his place in the squad's firing area. */
    JOIN_FIRE,

    /** He holds a nearby spot that keeps a shot and hides him best, the last resort of the compound. */
    HOLD_SPOT,
}

/**
 * What one engage is planned for: [actor], and with [forward] false no move that takes him towards
 * the enemy, for a man in the open under fire with no cover in reach (owner: no point-blank
 * assault, and no rush across open ground while shot at).
 */
data class EngageTask(val actor: AgentId, val forward: Boolean = true)

/**
 * The [number]th unit of [move] in [actor]'s engage. [sidearm] is the weapon he planned it on: a
 * switch is a new action, not the same one carried on.
 */
data class EngageUnit(val actor: AgentId, val move: EngageMove, val number: Int, val sidearm: Boolean, val armour: ArmourContact? = null)

/**
 * The conditions of the NPC domain's engage methods: which [EngageMove] a man in contact makes.
 *
 * The moves are tried in [ORDER], written once: a move applies when it [wants] to and no move
 * before it does, so exactly one applies and [EngageMove.HOLD_SPOT], which always wants to, is
 * the last resort. A new move is one entry in [ORDER] and one case in [wants].
 */
object Engage {
    /** The method id of [move] in `npc.engage`, the name the decision trace shows. */
    fun methodId(move: EngageMove) = if (move == EngageMove.HOLD_SPOT) "hold_firing_spot" else move.name.lowercase()

    /** The identity of [unit]'s action: its move, its number and his weapon, see [EngageUnit]. */
    fun identity(unit: EngageUnit) = "$IDENTITY${methodId(unit.move)}:${unit.number}:${if (unit.sidearm) "sidearm" else "primary"}"

    /** Whether an action [identity] is an engage unit's. */
    fun isUnit(identity: String) = identity.startsWith(IDENTITY)

    private const val IDENTITY = "engage:"

    /**
     * The moves, first wins. [EngageMove.FOLIAGE_FIRE] comes before the lane moves and the opening
     * burst: his gun stops observing a target hidden in foliage, so their facts stay as they were
     * when it vanished and would hold the bursts off until the episode ran out.
     */
    val ORDER = listOf(
        EngageMove.KEEP_AWAY,
        EngageMove.RETURN_TO_AREA,
        EngageMove.FOLIAGE_FIRE,
        EngageMove.SIDESTEP,
        EngageMove.RESEARCH_SPOT,
        EngageMove.OPEN_FIRE,
        EngageMove.ADVANCE_FROM_POST,
        EngageMove.CLOSE_IN,
        EngageMove.JOIN_FIRE,
        EngageMove.HOLD_SPOT,
    )

    /** The moves that take him towards the enemy: none of them for [EngageTask.forward] false or on a sidearm. */
    val FORWARD = setOf(EngageMove.ADVANCE_FROM_POST, EngageMove.CLOSE_IN, EngageMove.JOIN_FIRE)

    /**
     * A fresh target gets his opening fire within this many ticks of his first shootable line to
     * it, 5 s: long enough to aim and fire, short enough that a target he cannot hit does not
     * pin him in the open.
     */
    const val OPENING_WINDOW = 100L

    /**
     * Ticks a leg towards his slot that found no ground or path keeps [EngageMove.JOIN_FIRE] off,
     * 5 s. A man at the enemy clearance with no shot planned the leg again every tick, got none,
     * and stood blind with no task for 25 s (#291); meanwhile [EngageMove.HOLD_SPOT] looks for a
     * spot with a line from round where he stands.
     */
    const val SLOT_LEG_REST = 100L

    /** Rounds of an opening burst from an automatic weapon; a semi-automatic one fires one. */
    const val OPENING_ROUNDS_AUTOMATIC = 4

    /**
     * Lane checks in a row with an ally in it before he moves: two checks are 8 ticks, and an
     * ally cannot cross a lane in under a fifth of a second, so a passer-by moves no one. He
     * moves again on the first clear check.
     */
    const val LANE_DWELL_CHECKS = 2

    /**
     * A clear shot counts for this many ticks after the last one, 2 s: shorter than the shortest
     * pause of a bound (3 s), so a target hidden for part of one pause does not turn a bound into
     * a walk to the firing area halfway.
     */
    const val CLEAR_SHOT_TICKS = 40L

    /**
     * Blocks past a distance before a band fact turns back: a man at the edge of his reach or his
     * area moves a block back and forth while he fires, and must not flip his move each time.
     */
    const val BAND = 2.0

    /** Whether [move] would apply for [task], ignoring the moves before it. */
    fun wants(move: EngageMove, task: EngageTask, facts: FactView): Verdict =
        when (move) {
            EngageMove.KEEP_AWAY -> flag(facts, TacticalFacts.KEEP_AWAY, "no_one_too_near")
            EngageMove.RETURN_TO_AREA -> flag(facts, TacticalFacts.OUTSIDE_AREA, "inside_area")
            EngageMove.SIDESTEP -> flag(facts, TacticalFacts.LANE_BLOCKED_ALLY, "lane_clear_of_allies")
            EngageMove.RESEARCH_SPOT -> flag(facts, TacticalFacts.LANE_BLOCKED_HULL, "lane_clear_of_hulls")
            EngageMove.OPEN_FIRE -> flag(facts, TacticalFacts.OPENING_DUE, "opening_over")
            EngageMove.FOLIAGE_FIRE -> flag(facts, TacticalFacts.FOLIAGE_FIRE_DUE, "no_foliage_fire_due")
            EngageMove.ADVANCE_FROM_POST -> forward(task, facts) { all(flag(facts, TacticalFacts.HOLDING_ORDER, "not_holding"), flag(facts, TacticalFacts.MAY_ADVANCE, "advance_not_allowed")) }
            EngageMove.CLOSE_IN -> forward(task, facts) { all(not(facts, TacticalFacts.HOLDING_ORDER, "holding"), not(facts, TacticalFacts.IN_REACH, "in_reach"), flag(facts, TacticalFacts.CLEAR_SHOT, "no_clear_shot")) }
            EngageMove.JOIN_FIRE -> forward(task, facts) { all(not(facts, TacticalFacts.HOLDING_ORDER, "holding"), not(facts, TacticalFacts.IN_REACH, "in_reach"), not(facts, TacticalFacts.CLEAR_SHOT, "clear_shot"), not(facts, TacticalFacts.SLOT_LEG_BLOCKED, "no_slot_leg")) }
            EngageMove.HOLD_SPOT -> Verdict.Allowed
        }

    /** [move] wants to and no move before it in [ORDER] does; rejected as `preempted_by_<move>` otherwise. */
    fun applies(move: EngageMove, task: EngageTask) =
        Condition { facts ->
            for (earlier in ORDER.takeWhile { it != move }) {
                when (val verdict = wants(earlier, task, facts)) {
                    Verdict.Allowed -> return@Condition Verdict.Rejected("preempted_by_${earlier.name.lowercase()}")
                    is Verdict.Unknown -> return@Condition verdict
                    is Verdict.Rejected -> Unit
                }
            }
            wants(move, task, facts)
        }

    /** The move that applies on [facts], or null while a fact it needs is unknown. */
    fun choose(task: EngageTask, facts: FactView): EngageMove? = ORDER.firstOrNull { applies(it, task).evaluate(facts) == Verdict.Allowed }

    /**
     * Why a method of `npc.execute` other than `engage` stands aside for [intent]: in contact,
     * a man whose duty leaves his movement to his order engages instead.
     */
    fun standsAside(intent: PositionIntent, facts: FactView): Verdict? = if (!intent.commandsMovement && !intent.hold && facts.value(TacticalFacts.IN_CONTACT) == true) Verdict.Rejected("engages") else null

    /** He is in contact and nothing commands his movement: the `engage` method of `npc.execute`. */
    fun eligible(intent: PositionIntent) =
        Condition { facts ->
            when {
                intent.commandsMovement -> Verdict.Rejected("movement_commanded")
                intent.hold -> Verdict.Rejected("holds_as_ordered")
                else -> flag(facts, TacticalFacts.IN_CONTACT, "no_contact")
            }
        }

    /**
     * An engage unit's maintain: still in contact, not walking to resupply, and, for every move but
     * [EngageMove.FOLIAGE_FIRE], free to move. The bursts take no step: a man whose feet another
     * claim holds still fires them, as he did before they were planned; the rules that forbid the
     * fire itself are in their fact.
     */
    fun maintain(move: EngageMove): Condition {
        val fight = Condition.all(
            Condition.test(TacticalFacts.IN_CONTACT, "contact_lost") { it },
            Condition.test(TacticalFacts.RESUPPLYING, "resupplying") { !it },
        )
        return if (move == EngageMove.FOLIAGE_FIRE) fight else Condition.all(Condition.test(TacticalFacts.MOVABLE, "higher_priority_action") { it }, fight)
    }

    /**
     * [unit] is over once his executor has finished it ([TacticalFacts.ENGAGE_UNITS] past its
     * number, like a cover cycle), or once the fact its move [ends on][EngageMove.endsOn] lets go.
     */
    fun unitOver(unit: EngageUnit) =
        Condition { facts ->
            val units = facts.value(TacticalFacts.ENGAGE_UNITS) ?: return@Condition Verdict.Unknown(setOf(TacticalFacts.ENGAGE_UNITS.name))
            when {
                units > unit.number -> Verdict.Allowed
                unit.move.endsOn?.let { facts.value(it) } == false -> Verdict.Allowed
                else -> Verdict.Rejected("unit_running")
            }
        }

    /**
     * Ticks an engage unit may run before the plan gives it up, 20 s: twice its longest unit, a
     * hold of two looks of up to 100 ticks each, and a leg of a walk. A unit is seconds long, so
     * a stuck one is caught long before the minute a cover cycle gets.
     */
    const val UNIT_TIMEOUT = 400L
}

private fun forward(task: EngageTask, facts: FactView, verdict: () -> Verdict): Verdict =
    when {
        !task.forward -> Verdict.Rejected("no_forward_move")
        facts.value(TacticalFacts.SIDEARM) == null -> Verdict.Unknown(setOf(TacticalFacts.SIDEARM.name))
        facts.value(TacticalFacts.SIDEARM) == true -> Verdict.Rejected("sidearm")
        else -> verdict()
    }

/** The first rejection, else every unknown fact, else allowed: [Condition.all] for verdicts already made. */
private fun all(vararg verdicts: Verdict): Verdict {
    verdicts.firstOrNull { it is Verdict.Rejected }?.let { return it }
    val unknown = verdicts.filterIsInstance<Verdict.Unknown>().flatMapTo(LinkedHashSet()) { it.facts }
    return if (unknown.isEmpty()) Verdict.Allowed else Verdict.Unknown(unknown)
}

private fun flag(facts: FactView, key: FactKey<Boolean>, reason: String): Verdict = facts.value(key)?.let { if (it) Verdict.Allowed else Verdict.Rejected(reason) } ?: Verdict.Unknown(setOf(key.name))

private fun not(facts: FactView, key: FactKey<Boolean>, reason: String): Verdict = facts.value(key)?.let { if (it) Verdict.Rejected(reason) else Verdict.Allowed } ?: Verdict.Unknown(setOf(key.name))

/** A man's last resort in `npc.execute`: in contact his engage moves him, [forward] or not; out of it he carries out [hold]. */
internal fun engageOrHold(intent: PositionIntent, facts: FactView, forward: Boolean, hold: TaskCall<*>) = TaskNetwork.sequence(if (facts.value(TacticalFacts.IN_CONTACT) == true) TacticalDomain.ENGAGE_TASK.call(EngageTask(intent.actor, forward)) else hold)
