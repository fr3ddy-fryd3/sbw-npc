package com.sbwnpc.squad.entity.ai

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
}
