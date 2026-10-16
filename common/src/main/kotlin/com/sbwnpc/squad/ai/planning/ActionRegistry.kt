package com.sbwnpc.squad.ai.planning

/** The only erased cast is checked at this adapter boundary. Domain tasks keep typed parameters. */
class ActionBinding<P : Any>(val key: ActionKey<P>, private val create: (ExecutionTicket, P) -> ActionExecution) {
    internal fun instantiate(ticket: ExecutionTicket, spec: ActionSpec<*>): ActionExecution {
        require(spec.key == key && key.type.isInstance(spec.parameters))
        return create(ticket, key.type.cast(spec.parameters))
    }
}

class ActionRegistry(bindings: List<ActionBinding<*>>) : ActionFactory {
    private val bindings = bindings.associateBy { it.key }
    init {
        require(this.bindings.size == bindings.size) { "Duplicate action binding" }
        require(bindings.map { it.key.name }.distinct().size == bindings.size) { "Conflicting action names" }
    }
    override fun create(ticket: ExecutionTicket, spec: ActionSpec<*>): ActionExecution = requireNotNull(bindings[spec.key]) { "Unregistered action: ${spec.key.name}" }.instantiate(ticket, spec)
}
