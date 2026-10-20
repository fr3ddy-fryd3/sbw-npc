package com.sbwnpc.squad.entity

import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap

/**
 * Who holds each of an NPC's [NpcActivity]s, so one behaviour releasing an activity cannot clear
 * another's hold on it.
 *
 * Several behaviours write the same activity (six of them set [NpcActivity.VEHICLE]); with a bare
 * flag, whichever one stopped first cleared it for the rest (#102). Owners are compared by
 * identity, so claiming twice is the same as claiming once.
 */
class ActivityClaims {
    private val owners = EnumMap<NpcActivity, MutableSet<Any>>(NpcActivity::class.java)

    /**
     * Records [owner]'s hold on [activity]. Returns the activities in [held] that cannot hold
     * together with it ([NpcActivities.EXCLUSIVE]); the claim is recorded anyway, so the caller
     * decides what a conflict means.
     */
    fun claim(activity: NpcActivity, owner: Any, held: NpcActivities): Set<NpcActivity> {
        owners.getOrPut(activity) { Collections.newSetFromMap(IdentityHashMap()) } += owner
        return NpcActivities.excluding(activity).filterTo(HashSet()) { held.has(it) }
    }

    /**
     * Records [owner]'s hold on [activity] only if nobody else holds it, for an activity that has
     * one owner at a time. False when another owner has it; nothing is recorded then.
     */
    fun claimAlone(activity: NpcActivity, owner: Any): Boolean {
        if (!freeFor(activity, owner)) return false
        owners.getOrPut(activity) { Collections.newSetFromMap(IdentityHashMap()) } += owner
        return true
    }

    /** True when nobody but [owner] holds [activity]. */
    fun freeFor(activity: NpcActivity, owner: Any): Boolean = owners[activity].orEmpty().all { it === owner }

    /** Drops [owner]'s hold on [activity]; true when nobody else holds it, so the activity is over. */
    fun release(activity: NpcActivity, owner: Any): Boolean {
        val holders = owners[activity] ?: return true
        holders -= owner
        return holders.isEmpty()
    }

    fun holders(activity: NpcActivity): Int = owners[activity]?.size ?: 0
}
