package com.sbwnpc.squad.entity

import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap

/** What an NPC can be made to do by someone else's claim: walk, shoot, look around. */
enum class Cap { FEET, FIRE, LOOK }

/**
 * Everything an NPC can have taken up that other behaviours must respect, lowest priority first,
 * each claimed and released by the behaviour that owns it.
 *
 * Ranking applies per [Cap]: the highest kind that holds a capability owns it, a lower one is
 * suspended, not dropped, and owns it again when the higher one lets go. A kind that holds only the
 * feet leaves the trigger free. Roles rank above the movement claims because the movement
 * behaviours do not start for an NPC that has one (their eligibility lists say `busyWithRole`).
 *
 * Some are held together by design: a mortar crewman serves his tube while his crew sends him on an
 * errand, and the operator may carry the tube as well.
 */
enum class NpcActivity(val holds: Set<Cap>) {
    /**
     * Walking to the squad's order point ([com.sbwnpc.squad.entity.ai.SquadOrderBehaviour]). The
     * lowest claim: anything else that holds the feet takes it over. The trigger stays free.
     */
    ORDER(setOf(Cap.FEET)),

    /**
     * Walking to a post the squad planner chose ([com.sbwnpc.squad.entity.ai.SquadTacticalBehaviour]).
     * Above the squad order, below everything that needs the man where he stands.
     */
    TACTIC(setOf(Cap.FEET)),

    /**
     * Walking to a Supply: [com.sbwnpc.squad.entity.ai.ResupplyBehaviour] and the out-of-ammo
     * fallback. Feet only: the fallback walks while the NPC keeps firing its last rounds.
     */
    RESUPPLY(setOf(Cap.FEET)),

    /** A medic walking to or treating a critically wounded ally: [com.sbwnpc.squad.entity.ai.MedicHealBehaviour]. */
    HEAL(setOf(Cap.FEET, Cap.FIRE, Cap.LOOK)),

    /** Moving to, holding or leaving cover: [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour]. */
    COVER(setOf(Cap.FEET, Cap.FIRE)),

    /**
     * Stepping out of cover to return fire ([com.sbwnpc.squad.entity.ai.SeekCoverBehaviour]). The
     * cover claim is let go for the peek, so the rifle is free; the feet belong to the peek.
     */
    PEEK(setOf(Cap.FEET)),

    /** Running from a live grenade: [com.sbwnpc.squad.entity.ai.GrenadeEvadeBehaviour]. */
    EVADE(setOf(Cap.FEET, Cap.FIRE)),

    /**
     * Holding a foxhole it dug: [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour]. The man fights from
     * the hole, so the trigger stays free.
     */
    DUG_IN(setOf(Cap.FEET)),

    /** Walked somewhere by the mortar crew's own behaviour, see [com.sbwnpc.squad.entity.ai.MortarCrewBehaviour]. */
    MORTAR_ERRAND(setOf(Cap.FEET)),

    /** Carrying a broken-down mortar to a new position: [com.sbwnpc.squad.entity.ai.MortarDeployment]. */
    CARRYING_MORTAR(setOf(Cap.FEET)),

    /** Shooting at or running from a hostile drone: [com.sbwnpc.squad.entity.ai.AntiDroneBehaviour]. */
    ANTI_DRONE(setOf(Cap.FEET, Cap.FIRE, Cap.LOOK)),

    /** Preparing or flying a drone: [com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour]. */
    DRONE(setOf(Cap.FEET, Cap.FIRE, Cap.LOOK)),

    /** At a mortar as its operator or loader: [com.sbwnpc.squad.entity.ai.MortarCrewBehaviour]. */
    MORTAR_CREW(setOf(Cap.FEET, Cap.FIRE, Cap.LOOK)),

    /**
     * Getting into a vehicle, keeping a seat in one or getting out: held by one behaviour at a time,
     * see [NpcActivities.claimAlone].
     *
     * Not [VEHICLE], which cannot say whose a mounted man is: the pilot, the gunner and a crew's
     * driver hold it beside whoever seated him.
     */
    SEAT(setOf(Cap.FEET)),

    /** Seeking, boarding, driving or riding a vehicle: the vehicle and helicopter behaviours. */
    VEHICLE(setOf(Cap.FEET, Cap.FIRE, Cap.LOOK)),
}

/**
 * One NPC's claims, and the questions every behaviour asks of them.
 *
 * Several behaviours write the same kind (six of them claim [NpcActivity.VEHICLE]); with a bare
 * flag, whichever one stopped first cleared it for the rest (#102). Owners are compared by identity,
 * so a behaviour releasing a kind cannot clear another's hold on it, and claiming twice is the same
 * as claiming once. A takeover is told through [report], so the decision trace shows who stopped
 * whom.
 *
 * The questions live here, not at each caller, so a new kind is placed in each of them once and on
 * purpose; leaving one out is how a busy man got sent to look at a noise.
 *
 * `now` is the NPC's own tick count, for claims that end by themselves.
 */
class NpcActivities(private val report: (String) -> Unit = {}) {
    private class Hold(val owners: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap()), var until: Int = Int.MAX_VALUE)

    private val holds = EnumMap<NpcActivity, Hold>(NpcActivity::class.java)

    /**
     * [owner] takes or refreshes its [kind] claim; [lifetime] ticks from [now] if it should lapse
     * unrenewed. Returns the held kinds that cannot stand with it ([EXCLUSIVE]); the claim is
     * recorded anyway, so the caller decides what a conflict means.
     */
    fun claim(kind: NpcActivity, owner: Any, now: Int, lifetime: Int? = null): Set<NpcActivity> {
        val before = holder(Cap.FEET, now)
        val hold = holds.getOrPut(kind) { Hold() }
        val fresh = owner !in hold.owners
        hold.owners += owner
        hold.until = if (lifetime == null) Int.MAX_VALUE else now + lifetime
        if (fresh) {
            val after = holder(Cap.FEET, now)
            report("claimed $kind holder=$after" + if (before != null && after == kind && before != kind) " preempted=$before" else "")
        }
        return excluding(kind).filterTo(HashSet()) { has(it, now) }
    }

    /**
     * [owner] takes [kind] only if nobody else holds it, for a kind that has one owner at a time.
     * False when another owner has it; nothing is recorded then.
     */
    fun claimAlone(kind: NpcActivity, owner: Any, now: Int): Boolean {
        if (!freeFor(kind, owner)) return false
        claim(kind, owner, now)
        return true
    }

    /** [owner] lets go of its [kind] claim, if it holds one. */
    fun release(kind: NpcActivity, owner: Any, now: Int) {
        val hold = holds[kind] ?: return
        if (!hold.owners.remove(owner)) return
        if (hold.owners.isEmpty()) holds.remove(kind)
        val after = holder(Cap.FEET, now)
        report("released $kind holder=$after" + if (after != null && after < kind) " resumed=$after" else "")
    }

    /** Whether anybody holds [kind], owning its capabilities or suspended. */
    fun has(kind: NpcActivity, now: Int): Boolean = holds[kind]?.let { it.until > now } == true

    /** True when nobody but [owner] holds [kind]. */
    fun freeFor(kind: NpcActivity, owner: Any): Boolean = holds[kind]?.owners.orEmpty().all { it === owner }

    /** The highest live kind that takes [cap], or null when it is free. */
    fun holder(cap: Cap, now: Int): NpcActivity? = holds.entries.filter { it.value.until > now && cap in it.key.holds }.maxByOrNull { it.key.ordinal }?.key

    /** Whether nobody holds [cap]. */
    fun may(cap: Cap, now: Int): Boolean = holder(cap, now) == null

    /**
     * Whether [kind] may use [cap]: nothing ranked above [kind] holds it, setting aside the claims held
     * only by owners [own] accepts (a man's own plan actions take his feet as his plan, not from it, #251).
     */
    fun mayFor(kind: NpcActivity, cap: Cap, now: Int, own: ((Any) -> Boolean)? = null): Boolean =
        if (own == null) {
            (holder(cap, now)?.ordinal ?: -1) <= kind.ordinal
        } else {
            holds.entries.none { (held, hold) -> hold.until > now && cap in held.holds && held.ordinal > kind.ordinal && !hold.owners.all(own) }
        }

    /** A role owns the NPC's hands and feet, so its rifle, grenades and squad movement leave it alone. */
    fun ownsHandsAndFeet(now: Int): Boolean = ROLE_ACTIVITIES.any { has(it, now) }

    /** The mortar crew's errand, not the squad order, sets where and how fast it goes. */
    fun movingMortar(now: Int): Boolean = MORTAR_MOVE_ACTIVITIES.any { has(it, now) }

    companion object {
        /** The kinds behind [ownsHandsAndFeet]. */
        val ROLE_ACTIVITIES = setOf(NpcActivity.VEHICLE, NpcActivity.DRONE, NpcActivity.MORTAR_CREW, NpcActivity.ANTI_DRONE)

        /** The kinds behind [movingMortar]. */
        val MORTAR_MOVE_ACTIVITIES = setOf(NpcActivity.CARRYING_MORTAR, NpcActivity.MORTAR_ERRAND)

        /**
         * Pairs that should not be held together: a drone operator does not crew a mortar, and a man
         * dug in is not riding a vehicle. Only reported so far (see [claim]); the trace shows
         * whether any of them happens in play before a claim is refused.
         */
        private val EXCLUSIVE = listOf(
            NpcActivity.DRONE to NpcActivity.MORTAR_CREW,
            NpcActivity.DRONE to NpcActivity.VEHICLE,
            NpcActivity.MORTAR_CREW to NpcActivity.VEHICLE,
            NpcActivity.DUG_IN to NpcActivity.VEHICLE,
        )

        /** The kinds that [kind] should not be held together with. */
        fun excluding(kind: NpcActivity): Set<NpcActivity> =
            EXCLUSIVE.mapNotNullTo(HashSet()) { (a, b) ->
                if (a == kind) {
                    b
                } else if (b == kind) {
                    a
                } else {
                    null
                }
            }
    }
}
