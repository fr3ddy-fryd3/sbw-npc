package com.sbwnpc.squad.combat.tactics

/** Rotate bounded probes through known contacts, retaining a usable target instead of redistributing every scan. */
object TacticalTargetSelection {
    enum class Opportunity { BLOCKED, VISIBLE, SHOOTABLE }
    const val PROBES_PER_SCAN = 4

    fun <T : Any> choose(candidates: List<T>, current: T?, cursor: Int, assess: (T) -> Opportunity, allocate: (List<T>) -> T?): T? {
        if (candidates.isEmpty()) return null
        val retained = current?.takeIf { it in candidates }
        val currentOpportunity = retained?.let(assess)
        if (currentOpportunity == Opportunity.SHOOTABLE) return retained
        val probes = (0 until minOf(PROBES_PER_SCAN, candidates.size)).map {
            candidates[Math.floorMod(cursor + it, candidates.size)]
        }
        val opportunities = probes.associateWith { if (it == retained) currentOpportunity!! else assess(it) }
        val shooters = probes.filter { opportunities[it] == Opportunity.SHOOTABLE }
        if (shooters.isNotEmpty()) return allocate(shooters)
        val visible = probes.filter { opportunities[it] == Opportunity.VISIBLE }
        if (visible.isNotEmpty()) return allocate(visible)
        return retained ?: candidates.first()
    }
}
