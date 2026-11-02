package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.Cap
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity

/**
 * The rules the design lets decide outside the planner (#257): immediate reactions to danger,
 * medical help and the ammunition rules. Listed in their priority, highest first (owner, 2.11), with
 * what each takes from the man while it holds: a rule may start, and keeps going, only while no
 * rule above it holds a capability it takes ([MandatoryRules.blockedBy]).
 *
 * Each rule's own trigger stays with its behaviour, where the thing it reacts to is known (the
 * grenade it found, the drone it hears, the patient it chose); the order between rules is decided
 * here alone. A rule that takes his feet suspends his plan, and the decision trace names the rule
 * ([MandatoryRules.suspending]).
 *
 * Outside the table: the first duck under fire of the roles the planner does not cover yet
 * ([SeekCoverBehaviour]), and the roles that fly a drone or crew a tube, whose man stays at his post
 * even by a live grenade (they rank above [NpcActivity.EVADE]).
 *
 * @property activity the claim the rule's behaviour holds while it runs, or null for a rule that
 *   holds none: a kit used on the spot, or a trigger pulled while the plan keeps his feet.
 * @property owner whether a holder of [activity] is this rule's, for a kind two rules claim.
 */
enum class MandatoryRule(val id: String, val activity: NpcActivity?, val takes: Set<Cap>, private val owner: (Any) -> Boolean = { true }) {
    /** Run from a live grenade. */
    EVADE_GRENADE("evade_grenade", NpcActivity.EVADE, setOf(Cap.FEET, Cap.FIRE)),

    /** Shoot at or run from a hostile drone. */
    ANTI_DRONE("anti_drone", NpcActivity.ANTI_DRONE, setOf(Cap.FEET, Cap.FIRE, Cap.LOOK)),

    /** Treat his own wound on the spot, in safety or while treating a patient. */
    SELF_TREAT("self_treat", null, emptySet()),

    /** A medic goes to and treats a badly wounded ally. */
    MEDIC_HELP("medic_help", NpcActivity.HEAL, setOf(Cap.FEET, Cap.FIRE, Cap.LOOK)),

    /** Out of every round: to a Supply, or out of the fight. */
    OUT_OF_AMMO("out_of_ammo", NpcActivity.RESUPPLY, setOf(Cap.FEET), { it is OutOfAmmoBehaviour }),

    /** Low on ammunition: to a known Supply. */
    RESUPPLY("resupply", NpcActivity.RESUPPLY, setOf(Cap.FEET), { it is ResupplyBehaviour }),

    /** Fire back at fire he cannot see the source of: the trigger only, his plan keeps his feet. */
    BLIND_RETURN_FIRE("blind_return_fire", null, setOf(Cap.FIRE)),
    ;

    /** Whether this rule holds [npc] now. */
    fun active(npc: NpcEntity): Boolean = activity?.let { npc.holdsBy(it, owner) } == true
}

/** The questions every rule's behaviour asks of [MandatoryRule]'s order. */
object MandatoryRules {
    /** The rule above [rule] that holds [npc] on a capability [rule] takes, or null when none does. */
    fun blockedBy(rule: MandatoryRule, npc: NpcEntity): MandatoryRule? = MandatoryRule.entries.takeWhile { it != rule }.firstOrNull { higher -> higher.takes.any { it in rule.takes } && higher.active(npc) }

    /** Whether [rule] may start or go on for [npc]: no rule above it holds what it takes. */
    fun may(rule: MandatoryRule, npc: NpcEntity): Boolean = blockedBy(rule, npc) == null

    /** The highest rule holding [npc]'s feet, the one his plan is suspended for, or null. */
    fun suspending(npc: NpcEntity): MandatoryRule? = MandatoryRule.entries.firstOrNull { Cap.FEET in it.takes && it.active(npc) }
}
