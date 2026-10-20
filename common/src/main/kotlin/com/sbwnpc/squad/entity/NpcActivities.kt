package com.sbwnpc.squad.entity

/**
 * What an NPC has taken up that other behaviours must respect, each set and cleared by the
 * behaviour named on it.
 *
 * Some are on together by design: a mortar crewman serves his tube while his crew sends him on an
 * errand, and the operator may carry the tube as well. So an NPC holds a set of them, not one.
 */
enum class NpcActivity {
    /** Seeking, boarding, driving or riding a vehicle: the vehicle and helicopter behaviours. */
    VEHICLE,

    /** Flying a drone: [com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour]. */
    DRONE,

    /** At a mortar as its operator or loader: [com.sbwnpc.squad.entity.ai.MortarCrewBehaviour]. */
    MORTAR_CREW,

    /** Walked somewhere by the mortar crew's own behaviour, see [NpcEntity.mortarErrand]. */
    MORTAR_ERRAND,

    /** Carrying a broken-down mortar to a new position: [com.sbwnpc.squad.entity.ai.MortarDeployment]. */
    CARRYING_MORTAR,

    /** Shooting at or running from a hostile drone: [com.sbwnpc.squad.entity.ai.AntiDroneBehaviour]. */
    ANTI_DRONE,

    /** Walking to a Supply: [com.sbwnpc.squad.entity.ai.ResupplyBehaviour] and the out-of-ammo fallback. */
    RESUPPLYING,

    /** Holding a foxhole it dug: [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour]. */
    DUG_IN,

    /**
     * In or making for the vehicle a player sits in, to ride along with him:
     * [com.sbwnpc.squad.entity.ai.FollowRideBehaviour]. The transport behaviours leave such a man
     * be; taken as a ride of their own, they put him out again at once (#116).
     */
    RIDING_ALONG,
}

/**
 * One NPC's activities, and the questions every behaviour asks of them.
 *
 * The questions live here, not at each caller, so a new activity is placed in each of them once
 * and on purpose; leaving one out is how a busy man got sent to look at a noise. A bit mask, so
 * the NPC holds it in a plain int field: like the boolean flags it replaced, it reads as empty
 * even while the vanilla constructor is still running.
 */
@JvmInline
value class NpcActivities(private val mask: Int = 0) {
    fun has(activity: NpcActivity): Boolean = mask and bit(activity) != 0

    /** These activities with [activity] turned [on] or off. */
    fun with(activity: NpcActivity, on: Boolean): NpcActivities = NpcActivities(if (on) mask or bit(activity) else mask and bit(activity).inv())

    /** A role owns the NPC's hands and feet, so its rifle, grenades and squad movement leave it alone. */
    fun ownsHandsAndFeet(): Boolean = mask and ROLE != 0

    /** The mortar crew's errand, not the squad order, sets where and how fast it goes. */
    fun movingMortar(): Boolean = mask and MORTAR_MOVE != 0

    companion object {
        private fun bit(activity: NpcActivity) = 1 shl activity.ordinal

        private fun maskOf(activities: Set<NpcActivity>) = activities.fold(0) { mask, activity -> mask or bit(activity) }

        /** The activities behind [ownsHandsAndFeet]. */
        val ROLE_ACTIVITIES = setOf(NpcActivity.VEHICLE, NpcActivity.DRONE, NpcActivity.MORTAR_CREW, NpcActivity.ANTI_DRONE)

        /** The activities behind [movingMortar]. */
        val MORTAR_MOVE_ACTIVITIES = setOf(NpcActivity.CARRYING_MORTAR, NpcActivity.MORTAR_ERRAND)

        /**
         * Pairs that should not be held together: a drone operator does not crew a mortar, and a man
         * dug in is not riding a vehicle. Only reported so far (see [ActivityClaims.claim]); the
         * trace shows whether any of them happens in play before a claim is refused.
         */
        private val EXCLUSIVE = listOf(
            NpcActivity.DRONE to NpcActivity.MORTAR_CREW,
            NpcActivity.DRONE to NpcActivity.VEHICLE,
            NpcActivity.MORTAR_CREW to NpcActivity.VEHICLE,
            NpcActivity.DUG_IN to NpcActivity.VEHICLE,
        )

        /** The activities that [activity] should not be held together with. */
        fun excluding(activity: NpcActivity): Set<NpcActivity> =
            EXCLUSIVE.mapNotNullTo(HashSet()) { (a, b) ->
                if (a == activity) {
                    b
                } else if (b == activity) {
                    a
                } else {
                    null
                }
            }

        private val ROLE = maskOf(ROLE_ACTIVITIES)
        private val MORTAR_MOVE = maskOf(MORTAR_MOVE_ACTIVITIES)
    }
}
