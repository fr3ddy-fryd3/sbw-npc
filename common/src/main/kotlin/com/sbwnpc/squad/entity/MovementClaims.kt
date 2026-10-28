package com.sbwnpc.squad.entity

import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap

/**
 * What can hold an NPC's movement, lowest priority first. The highest claim held owns it; a lower
 * one is suspended, not dropped, and owns it again when the higher one lets go.
 */
enum class MovementClaim {
    /** Walking to a Supply: [com.sbwnpc.squad.entity.ai.ResupplyBehaviour] and the out-of-ammo fallback. */
    RESUPPLY,

    /** A medic walking to or treating a critically wounded ally: [com.sbwnpc.squad.entity.ai.MedicHealBehaviour]. */
    HEAL,

    /** Moving to, holding or leaving cover: [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour]. */
    COVER,

    /** Running from a live grenade: [com.sbwnpc.squad.entity.ai.GrenadeEvadeBehaviour]. */
    EVADE,
}

/**
 * Who holds an NPC's movement, and which of them wins.
 *
 * Movement used to be whatever called `navigation` first in a tick, with one flag per conflict found
 * in play. A behaviour now claims it with a priority; a higher claim takes over and the one it
 * outranked is told through [report], so the decision trace shows who stopped whom. Owners are
 * compared by identity, so one behaviour releasing a kind cannot clear another's hold on it
 * (see [ActivityClaims]).
 *
 * [now] is the NPC's own tick count, for claims that end by themselves.
 */
class MovementClaims(private val report: (String) -> Unit = {}) {
    private class Hold(val owners: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap()), var until: Int = Int.MAX_VALUE)

    private val holds = EnumMap<MovementClaim, Hold>(MovementClaim::class.java)

    /** [owner] takes or refreshes its [kind] claim; [lifetime] ticks from [now] if it should lapse unrenewed. */
    fun claim(owner: Any, kind: MovementClaim, now: Int, lifetime: Int? = null) {
        val before = owner(now)
        val hold = holds.getOrPut(kind) { Hold() }
        val fresh = owner !in hold.owners
        hold.owners += owner
        hold.until = if (lifetime == null) Int.MAX_VALUE else now + lifetime
        if (!fresh) return
        val after = owner(now)
        report("claimed $kind owner=$after" + if (before != null && after == kind && before != kind) " preempted=$before" else "")
    }

    /** [owner] lets go of its [kind] claim, if it holds one. */
    fun release(owner: Any, kind: MovementClaim, now: Int) {
        val hold = holds[kind] ?: return
        if (!hold.owners.remove(owner)) return
        if (hold.owners.isEmpty()) holds.remove(kind)
        val after = owner(now)
        report("released $kind owner=$after" + if (after != null && after < kind) " resumed=$after" else "")
    }

    /** The claim that owns movement, or null when nobody holds it. */
    fun owner(now: Int): MovementClaim? = holds.entries.filter { it.value.until > now }.maxByOrNull { it.key.ordinal }?.key

    /** Whether any claim of [kind] is held, owning movement or suspended. */
    fun has(kind: MovementClaim, now: Int): Boolean = holds[kind]?.let { it.until > now } == true

    /** Whether [owner]'s own claim is the one that owns movement. */
    fun ownedBy(owner: Any, now: Int): Boolean {
        val top = owner(now) ?: return false
        return holds[top]?.owners?.contains(owner) == true
    }
}
