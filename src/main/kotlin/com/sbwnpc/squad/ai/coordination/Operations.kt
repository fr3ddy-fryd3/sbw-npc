package com.sbwnpc.squad.ai.coordination

import java.util.UUID
import kotlin.math.abs

enum class TacticalOrder { ATTACK, DEFEND, MOVE, RETREAT, PATROL, BARRAGE }
data class Point3(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite())
    }
    fun horizontalDistanceSquared(other: Point3): Double = (x - other.x) * (x - other.x) + (z - other.z) * (z - other.z)
}
sealed interface ObjectiveKey {
    val dimension: String
    data class Area(override val dimension: String, val x: Int, val y: Int, val z: Int) : ObjectiveKey
    data class Entity(override val dimension: String, val id: UUID) : ObjectiveKey
    data class Structure(override val dimension: String, val id: UUID) : ObjectiveKey
}
data class EntryArea(val center: Point3, val radius: Double, val verticalTolerance: Double) {
    init {
        require(radius.isFinite() && radius > 0 && verticalTolerance.isFinite() && verticalTolerance >= 0)
    }
    fun contains(position: Point3) = center.horizontalDistanceSquared(position) <= radius * radius && abs(center.y - position.y) <= verticalTolerance
}
data class SquadParticipant(val id: UUID, val principal: UUID, val faction: String, val dimension: String, val orderVersion: Long)
data class CommandBatch(val id: UUID, val principal: UUID, val order: TacticalOrder, val objective: ObjectiveKey, val participants: List<SquadParticipant>, val issuedAt: Long, val entry: EntryArea)
enum class OperationStatus { ACTIVE, COMPLETED, CANCELLED }
data class OperationView(val id: UUID, val principal: UUID, val order: TacticalOrder, val objective: ObjectiveKey, val issuedAt: Long, val entry: EntryArea, val members: Map<UUID, SquadParticipant>, val status: OperationStatus)
data class PendingReinforcement(val participant: SquadParticipant, val command: UUID, val operation: UUID, val issuedAt: Long)
data class ArrivalReport(val squad: UUID, val orderVersion: Long, val operation: UUID, val observedAt: Long, val position: Point3, val safe: Boolean, val combatReady: Boolean)
sealed interface OperationCommandResult {
    data class Started(val operation: OperationView) : OperationCommandResult
    data class Reinforcement(val pending: PendingReinforcement) : OperationCommandResult
    data class Rejected(val reason: String) : OperationCommandResult
}

/** One explicit common command creates an operation; nearby fighting does not merge commands. */
class OperationRegistry(
    private val allied: (String, String) -> Boolean,
    private val allowed: (SquadParticipant, TacticalOrder) -> Boolean,
    private val arrivalFreshness: Long = 10,
) {
    private val operations = LinkedHashMap<UUID, OperationView>()
    private val membership = HashMap<UUID, UUID>()
    private val reinforcements = HashMap<UUID, PendingReinforcement>()
    init {
        require(arrivalFreshness >= 0)
    }

    fun command(batch: CommandBatch): OperationCommandResult {
        val squads = batch.participants
        if (squads.isEmpty() || squads.map { it.id }.distinct().size != squads.size) return OperationCommandResult.Rejected("invalid_selection")
        if (operations.containsKey(batch.id) || reinforcements.values.any { it.command == batch.id }) return OperationCommandResult.Rejected("duplicate_command")
        if (batch.objective.dimension.isBlank() || squads.any { it.dimension != batch.objective.dimension }) return OperationCommandResult.Rejected("wrong_dimension")
        if (squads.any { it.principal != batch.principal }) return OperationCommandResult.Rejected("no_command_authority")
        if (squads.any { !allowed(it, batch.order) }) return OperationCommandResult.Rejected("unsupported_order")
        if (squads.any { a -> squads.any { b -> !allied(a.faction, b.faction) } }) return OperationCommandResult.Rejected("incompatible_factions")

        // Matching is fixed before arrival. A new common command remains its own operation.
        val joining = if (squads.size == 1) {
            operations.values.firstOrNull { operation ->
                operation.status == OperationStatus.ACTIVE && operation.principal == batch.principal &&
                    operation.order == batch.order && operation.objective == batch.objective &&
                    operation.members.values.all { allied(it.faction, squads.single().faction) }
            }
        } else {
            null
        }
        squads.forEach { detach(it.id) }
        if (joining != null && operations.getValue(joining.id).status == OperationStatus.ACTIVE) {
            val pending = PendingReinforcement(squads.single(), batch.id, joining.id, batch.issuedAt)
            reinforcements[pending.participant.id] = pending
            return OperationCommandResult.Reinforcement(pending)
        }
        val operation = OperationView(batch.id, batch.principal, batch.order, batch.objective, batch.issuedAt, batch.entry, squads.associateBy { it.id }, OperationStatus.ACTIVE)
        operations[operation.id] = operation
        squads.forEach { membership[it.id] = operation.id }
        return OperationCommandResult.Started(operation)
    }

    fun arrive(report: ArrivalReport, now: Long): Boolean {
        val pending = reinforcements[report.squad] ?: return false
        val operation = operations[pending.operation] ?: return false
        if (report.operation != pending.operation || report.orderVersion != pending.participant.orderVersion || operation.status != OperationStatus.ACTIVE) return false
        if (report.observedAt !in pending.issuedAt..now || now - report.observedAt > arrivalFreshness) return false
        if (!report.safe || !report.combatReady || !operation.entry.contains(report.position)) return false
        if (!operation.members.values.all { allied(it.faction, pending.participant.faction) }) return false
        operations[operation.id] = operation.copy(members = operation.members + (report.squad to pending.participant))
        membership[report.squad] = operation.id
        reinforcements.remove(report.squad)
        return true
    }

    fun operation(id: UUID): OperationView? = operations[id]
    fun operationOf(squad: UUID): OperationView? = membership[squad]?.let(operations::get)
    fun pending(squad: UUID): PendingReinforcement? = reinforcements[squad]

    fun detach(squad: UUID) {
        reinforcements.remove(squad)
        val id = membership.remove(squad) ?: return
        val old = operations.getValue(id)
        val members = old.members - squad
        operations[id] = old.copy(members = members, status = if (members.isEmpty()) OperationStatus.CANCELLED else old.status)
    }

    fun finish(id: UUID, status: OperationStatus) {
        require(status != OperationStatus.ACTIVE)
        val old = operations[id] ?: return
        if (old.status != OperationStatus.ACTIVE) return
        operations[id] = old.copy(status = status)
        old.members.keys.forEach { membership.remove(it, id) }
        // The caller keeps each pending squad's own command context and chooses its next task.
        reinforcements.entries.removeIf { it.value.operation == id }
    }

    fun forget(id: UUID): Boolean {
        val operation = operations[id] ?: return false
        if (operation.status == OperationStatus.ACTIVE) return false
        operations.remove(id)
        return true
    }
}
