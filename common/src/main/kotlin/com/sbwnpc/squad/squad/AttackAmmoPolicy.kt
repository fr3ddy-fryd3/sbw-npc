package com.sbwnpc.squad.squad

/** A failed attack offers one explicit retry; routine order/objective updates cannot take it. */
enum class AttackAmmoPolicy {
    WITHDRAW_WHEN_EMPTY,
    RETRY_AVAILABLE,
    PRESS_ON,
}
