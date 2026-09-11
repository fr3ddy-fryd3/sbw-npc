package com.sbwnpc.squad.entity.ai

import java.util.UUID

/** Transient "which NPC is manning which mortar" registry, so two operators don't fight over one. */
object MortarClaims {
    private val byMortar = HashMap<UUID, UUID>()

    fun claim(mortar: UUID, operator: UUID): Boolean {
        val holder = byMortar[mortar]
        if (holder != null && holder != operator) return false
        byMortar[mortar] = operator
        return true
    }

    fun release(operator: UUID) {
        byMortar.entries.removeIf { it.value == operator }
    }

    fun isClaimedByOther(mortar: UUID, operator: UUID): Boolean {
        val holder = byMortar[mortar] ?: return false
        return holder != operator
    }
}
