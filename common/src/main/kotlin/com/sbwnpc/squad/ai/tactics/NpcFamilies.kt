package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.FactView
import com.sbwnpc.squad.ai.planning.Verdict

/**
 * The families of `npc.execute` that take a man over before his duty's own methods: his answer to
 * fire ([UnderFire]), his drone ([DroneStrike], ahead of fire while it is up) and his engage ([Engage]). Every duty method stands aside through this one
 * gate, so a new family is one more line here, not an edit to every method.
 */
object NpcFamilies {
    /** Why a duty method stands aside for [intent], or null when it may apply. */
    fun standsAside(intent: PositionIntent, facts: FactView): Verdict? = DroneStrike.flying(facts) ?: ArmourResponse.standsAside(intent, facts) ?: UnderFire.duty(intent, facts) ?: DroneStrike.standsAside(facts) ?: Engage.standsAside(intent, facts)
}
