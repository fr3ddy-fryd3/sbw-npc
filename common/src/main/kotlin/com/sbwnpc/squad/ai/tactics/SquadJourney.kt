package com.sbwnpc.squad.ai.tactics

/**
 * What a squad's men see close by that bears on its journey: an enemy NPC or player within a man's
 * engagement distance ([enemy]), or a mob going for them within half a mob's engagement range ([mob]).
 * Fire from farther off, wariness and alertness are not contact: they never keep a squad from
 * leaving (owner, 28.10, #231).
 */
data class JourneyContact(val enemy: Boolean, val mob: Boolean) {
    companion object {
        val NONE = JourneyContact(enemy = false, mob = false)
    }
}

/**
 * Whether a squad travels to its objective or fights where it is: one decision for the whole squad
 * (#255), where each man used to decide for himself and one boarded while another fought (#198).
 */
object SquadJourney {
    /**
     * A squad far from its objective ([distant]) travels unless an enemy is close. A mob close by
     * keeps a squad from setting off, but once it is [travelling] a mob closing in does not stop the
     * trip: whoever sees it shoots from where he is. An enemy squad or player close by always does.
     *
     * A squad that has [fought] its attack's defence under this order never travels again: the
     * attack moves it from there. Defenders dropping in and out of sight otherwise sent it back to
     * the march each time, walking at them with no tactics (#312).
     */
    fun travels(distant: Boolean, contact: JourneyContact, travelling: Boolean, fought: Boolean): Boolean = !fought && distant && !contact.enemy && (travelling || !contact.mob)
}
