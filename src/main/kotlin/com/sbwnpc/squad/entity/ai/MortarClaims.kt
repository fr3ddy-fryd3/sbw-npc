package com.sbwnpc.squad.entity.ai

import java.util.UUID

/** Transient "who's manning / loading which mortar" registry, so operators and loaders don't fight
 *  each other for the same post. Operator and loader are independent roles on the same mortar. */
object MortarClaims {
    private val operators = HashMap<UUID, UUID>()
    private val loaders = HashMap<UUID, UUID>()

    fun claimOperator(mortar: UUID, npc: UUID) = claim(operators, mortar, npc)
    fun releaseOperator(npc: UUID) = release(operators, npc)
    fun isOperatorClaimedByOther(mortar: UUID, npc: UUID) = isClaimedByOther(operators, mortar, npc)

    fun claimLoader(mortar: UUID, npc: UUID) = claim(loaders, mortar, npc)
    fun releaseLoader(npc: UUID) = release(loaders, npc)
    fun isLoaderClaimedByOther(mortar: UUID, npc: UUID) = isClaimedByOther(loaders, mortar, npc)

    fun release(npc: UUID) {
        release(operators, npc)
        release(loaders, npc)
    }

    private fun claim(map: HashMap<UUID, UUID>, mortar: UUID, npc: UUID): Boolean {
        val holder = map[mortar]
        if (holder != null && holder != npc) return false
        map[mortar] = npc
        return true
    }

    private fun release(map: HashMap<UUID, UUID>, npc: UUID) {
        map.entries.removeIf { it.value == npc }
    }

    private fun isClaimedByOther(map: HashMap<UUID, UUID>, mortar: UUID, npc: UUID): Boolean {
        val holder = map[mortar] ?: return false
        return holder != npc
    }
}
