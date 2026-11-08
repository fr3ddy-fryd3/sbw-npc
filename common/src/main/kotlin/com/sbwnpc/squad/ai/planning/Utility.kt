package com.sbwnpc.squad.ai.planning

import kotlin.math.exp

/**
 * One factor of a score: its [value] on the 0..1 scale and, for a [Consideration], the [input] it
 * was mapped from. An [unknown] factor had no data and counts as 1 (no effect), so it neither
 * vetoes the option nor makes it look better than one whose data is known.
 */
data class UtilityFactor(val name: String, val value: Double, val weight: Double = 1.0, val input: Double? = null, val unknown: Boolean = false) {
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

    /** The score with each factor beyond the base, `input>value` or `?` when unknown, for the decision trace. */
    fun explain(): String {
        val shown = factors.filter { it.name != "base" }
        if (shown.isEmpty()) return "%.2f".format(value)
        return "%.2f".format(value) + shown.joinToString(" ", " [", "]") { factor ->
            when {
                factor.unknown -> "${factor.name}?"
                factor.input != null -> "${factor.name} %.2f>%.2f".format(factor.input, factor.value)
                else -> "${factor.name} %.2f".format(factor.value)
            }
        }
    }

    companion object {
        val NEUTRAL = UtilityScore(0.5)

        /**
         * [base] times the product of [considerations], each lifted by Dave Mark's compensation for
         * their number (`v + (1 - v) * (1 - 1/n) * v`), so an option is not punished just for
         * weighing more factors. A consideration at 0 vetoes the option; one at 1, or unknown,
         * leaves [base] as it is.
         */
        fun product(base: Double, considerations: List<Consideration>, reason: String = ""): UtilityScore {
            require(base.isFinite() && base in 0.0..1.0)
            val modification = if (considerations.isEmpty()) 0.0 else 1.0 - 1.0 / considerations.size
            val factors = considerations.map { it.factor() }
            val product = factors.fold(1.0) { total, factor -> total * (factor.value + (1.0 - factor.value) * modification * factor.value) }
            return UtilityScore((base * product).coerceIn(0.0, 1.0), listOf(UtilityFactor("base", base)) + factors, reason)
        }

        fun weighted(factors: List<UtilityFactor>, reason: String = ""): UtilityScore {
            require(factors.isNotEmpty() && factors.sumOf { it.weight } > 0.0)
            return UtilityScore(factors.sumOf { it.value * it.weight } / factors.sumOf { it.weight }, factors.toList(), reason)
        }
    }
}

/** Maps a consideration's input onto 0..1. Each shape is pure and clamped. */
fun interface ResponseCurve {
    fun at(input: Double): Double

    companion object {
        /** 0 at [from], 1 at [to], straight between; [from] above [to] makes it fall. */
        fun linear(from: Double, to: Double) = ResponseCurve { x -> ((x - from) / (to - from)).coerceIn(0.0, 1.0) }

        /** An S rising through 0.5 at [midpoint]; a negative [steepness] makes it fall. */
        fun logistic(midpoint: Double, steepness: Double) = ResponseCurve { x -> 1.0 / (1.0 + exp(-steepness * (x - midpoint))) }

        /** [floor] at [from], 1 at [to], linear between: a factor that weakens an option but never vetoes it. */
        fun floored(floor: Double, from: Double, to: Double) = ResponseCurve { x -> floor + (1.0 - floor) * linear(from, to).at(x) }
    }
}

/** A named input mapped through its [curve]; a null [input] is unknown data. */
class Consideration(val name: String, val input: Double?, val curve: ResponseCurve) {
    fun factor() = if (input == null) UtilityFactor(name, 1.0, unknown = true) else UtilityFactor(name, curve.at(input).coerceIn(0.0, 1.0), input = input)
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
