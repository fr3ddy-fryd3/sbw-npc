package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.phys.Vec3

/** A quiet attack completes even at an empty objective; new contact restarts the interval. */
class HelicopterAttackCompletion {
    private var stamp = Int.MIN_VALUE
    private var lastContact: Long? = null

    fun observe(orderStamp: Int, now: Long, engaged: Boolean): Boolean {
        if (stamp != orderStamp) {
            stamp = orderStamp
            lastContact = now
        }
        if (engaged) lastContact = now
        return !engaged && lastContact?.let { now - it >= 200 } == true
    }

    companion object {
        /** Over the crew's detection range: a gunship loitering 70 out and 14 up is about 71.4 from the objective. */
        private const val SURVEY_SLACK = 8.0

        /**
         * Within this of the objective, in three dimensions, the crew could have seen anyone there.
         * The 72-block sight it was set from (#119), not today's [NpcEntity.DETECTION_RANGE]: that
         * grew to 128 as the bright-light reach, and a crew held 130 up would count as looking.
         */
        const val SURVEY_RANGE = 72.0 + SURVEY_SLACK

        /**
         * Whether an aircraft at [aircraft] is close enough to [objective] for a quiet interval to
         * mean nobody is there. Measured to the objective itself, not as height over the ground
         * below: an orbit swings over lower and higher ground than the objective's.
         */
        fun surveys(aircraft: Vec3, objective: Vec3): Boolean = aircraft.distanceTo(objective) <= SURVEY_RANGE
    }
}
