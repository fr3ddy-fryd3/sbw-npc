package com.sbwnpc.squad.ai.planning

data class UtilityFactor(val name: String, val value: Double, val weight: Double = 1.0) {
    init {
        require(name.isNotBlank() && value.isFinite() && value in 0.0..1.0)
        require(weight.isFinite() && weight >= 0.0)
    }
}

/** Eligibility is checked separately. The number is preference, never success probability. */
data class UtilityScore(val value: Double, val factors: List<UtilityFactor> = emptyList(), val reason: String = "") {
    init {
        require(value.isFinite() && value in 0.0..1.0)
    }
    companion object {
        val NEUTRAL = UtilityScore(0.5)
        fun weighted(factors: List<UtilityFactor>, reason: String = ""): UtilityScore {
            require(factors.isNotEmpty() && factors.sumOf { it.weight } > 0.0)
            return UtilityScore(factors.sumOf { it.value * it.weight } / factors.sumOf { it.weight }, factors.toList(), reason)
        }
    }
}

data class DecisionCandidate<T : Any>(val id: String, val value: T, val eligibility: Condition, val score: (FactView) -> UtilityScore)
data class RankedDecision<T : Any>(val id: String, val value: T, val score: UtilityScore)
data class ExcludedDecision(val id: String, val verdict: Verdict)
data class DecisionEvaluation<T : Any>(val ranked: List<RankedDecision<T>>, val excluded: List<ExcludedDecision>, val dependencies: List<FactDependency>)

object UtilityEvaluation {
    fun <T : Any> rank(facts: FactSnapshot, candidates: List<DecisionCandidate<T>>): DecisionEvaluation<T> {
        require(candidates.map { it.id }.distinct().size == candidates.size)
        val view = TrackingFacts(facts, facts, emptySet())
        val ranked = ArrayList<RankedDecision<T>>()
        val excluded = ArrayList<ExcludedDecision>()
        for (candidate in candidates) {
            when (val verdict = candidate.eligibility.evaluate(view)) {
                Verdict.Allowed -> ranked += RankedDecision(candidate.id, candidate.value, candidate.score(view))
                else -> excluded += ExcludedDecision(candidate.id, verdict)
            }
        }
        return DecisionEvaluation(ranked.sortedWith(compareByDescending<RankedDecision<T>> { it.score.value }.thenBy { it.id }), excluded, view.dependencies.values.toList())
    }
}
