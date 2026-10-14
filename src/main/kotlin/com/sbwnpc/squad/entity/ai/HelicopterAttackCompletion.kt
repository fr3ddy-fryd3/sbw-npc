package com.sbwnpc.squad.entity.ai

/** A new attack must see contact before a quiet interval can complete it. */
class HelicopterAttackCompletion {
    private var stamp = Int.MIN_VALUE
    private var lastContact: Long? = null

    fun observe(orderStamp: Int, now: Long, engaged: Boolean): Boolean {
        if (stamp != orderStamp) {
            stamp = orderStamp
            lastContact = null
        }
        if (engaged) lastContact = now
        return !engaged && lastContact?.let { now - it >= 200 } == true
    }
}
