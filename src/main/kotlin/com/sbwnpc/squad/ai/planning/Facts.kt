package com.sbwnpc.squad.ai.planning

/** Keys and values belong to one agent's immutable, observed world view. */
data class FactKey<T : Any>(val name: String, val type: Class<T>) {
    init {
        require(name.isNotBlank())
    }
}

data class FactRecord<T : Any>(
    val value: T,
    val observedAt: Long,
    val availableAt: Long,
    val expiresAt: Long,
    val source: String,
) {
    init {
        require(availableAt >= observedAt && expiresAt > observedAt)
        require(source.isNotBlank())
    }
    fun usable(now: Long) = observedAt <= now && availableAt <= now && now < expiresAt
}

interface FactView {
    val now: Long
    val owner: AgentId
    fun <T : Any> value(key: FactKey<T>): T?
    fun <T : Any> record(key: FactKey<T>): FactRecord<T>?
}

/** Updating observation time alone does not change a value dependency. */
class FactSnapshot private constructor(override val owner: AgentId, override val now: Long, private val entries: Map<FactKey<*>, FactRecord<*>>) : FactView {
    constructor(owner: AgentId, now: Long) : this(owner, now, emptyMap())
    override fun <T : Any> value(key: FactKey<T>): T? = record(key)?.value

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> record(key: FactKey<T>): FactRecord<T>? = entries[key]?.takeIf { it.usable(now) } as FactRecord<T>?
    fun at(now: Long) = FactSnapshot(owner, now, entries)
    fun <T : Any> observe(key: FactKey<T>, value: T, observedAt: Long = now, availableAt: Long = observedAt, expiresAt: Long = Long.MAX_VALUE, source: String = "observation"): FactSnapshot {
        require(key.type.isInstance(value)) { "Wrong value type for ${key.name}" }
        require(entries.keys.none { it.name == key.name && it != key }) { "Conflicting fact types: ${key.name}" }
        return FactSnapshot(owner, now, entries + (key to FactRecord(value, observedAt, availableAt, expiresAt, source)))
    }
    fun forget(key: FactKey<*>) = FactSnapshot(owner, now, entries - key)
    internal fun signature(key: FactKey<*>, fullRecord: Boolean): Any? = entries[key]?.takeIf { it.usable(now) }?.let { if (fullRecord) it else it.value }
    internal fun signatureByName(name: String): Any? = entries.keys.firstOrNull { it.name == name }?.let { signature(it, false) }
    internal fun project(effects: Effects): FactView = ProjectedFacts(this, effects)
}

/** This type cannot be passed to a runtime API accepting actual observations. */
private class ProjectedFacts(private val observed: FactSnapshot, private val effects: Effects) : FactView {
    override val owner get() = observed.owner
    override val now get() = observed.now
    override fun <T : Any> value(key: FactKey<T>): T? = record(key)?.value
    override fun <T : Any> record(key: FactKey<T>): FactRecord<T>? =
        effects.values[key]?.let {
            FactRecord(key.type.cast(it), now, now, Long.MAX_VALUE, "prediction")
        } ?: observed.record(key)
}

data class FactDependency(val key: FactKey<*>, val fullRecord: Boolean, val signature: Any?) {
    fun unchanged(view: FactSnapshot) = view.signature(key, fullRecord) == signature
}

internal class TrackingFacts(private val projected: FactView, private val observed: FactSnapshot, private val predicted: Set<FactKey<*>>) : FactView {
    override val now get() = projected.now
    override val owner get() = observed.owner
    val dependencies = LinkedHashMap<Pair<FactKey<*>, Boolean>, FactDependency>()
    private fun read(key: FactKey<*>, full: Boolean) {
        if (key !in predicted) dependencies[key to full] = FactDependency(key, full, observed.signature(key, full))
    }
    override fun <T : Any> value(key: FactKey<T>): T? {
        read(key, false)
        return projected.value(key)
    }
    override fun <T : Any> record(key: FactKey<T>): FactRecord<T>? {
        read(key, true)
        return projected.record(key)
    }
}

/** Predictions are declarative planning data; runtime never applies them to observations. */
class Effects private constructor(internal val values: Map<FactKey<*>, Any>) {
    fun <T : Any> set(key: FactKey<T>, value: T): Effects {
        require(key.type.isInstance(value))
        return Effects(values + (key to value))
    }
    companion object {
        val NONE = Effects(emptyMap())
    }
}

sealed interface Verdict {
    data object Allowed : Verdict
    data class Rejected(val reason: String) : Verdict
    data class Unknown(val facts: Set<String>) : Verdict
}

fun interface Condition {
    fun evaluate(facts: FactView): Verdict
    companion object {
        val ALWAYS = Condition { Verdict.Allowed }
        fun all(vararg conditions: Condition) =
            Condition { facts ->
                val unknown = LinkedHashSet<String>()
                var rejected: Verdict.Rejected? = null
                for (condition in conditions) {
                    when (val verdict = condition.evaluate(facts)) {
                        Verdict.Allowed -> Unit

                        is Verdict.Rejected -> {
                            rejected = verdict
                            break
                        }

                        is Verdict.Unknown -> unknown += verdict.facts
                    }
                }
                rejected ?: if (unknown.isEmpty()) Verdict.Allowed else Verdict.Unknown(unknown)
            }
        fun <T : Any> test(key: FactKey<T>, reason: String, predicate: (T) -> Boolean) =
            Condition { facts ->
                facts.value(key)?.let { if (predicate(it)) Verdict.Allowed else Verdict.Rejected(reason) } ?: Verdict.Unknown(setOf(key.name))
            }
    }
}
