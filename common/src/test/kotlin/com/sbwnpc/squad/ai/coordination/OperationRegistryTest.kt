package com.sbwnpc.squad.ai.coordination

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class OperationRegistryTest {
    private val principal = UUID(0, 1)
    private val objective = ObjectiveKey.Area("world", 100, 64, 100)
    private val entry = EntryArea(Point3(90.0, 64.0, 100.0), 12.0, 5.0)
    private fun squad(id: Long, faction: String = "a", version: Long = 1) = SquadParticipant(UUID(0, id), principal, faction, "world", version)
    private fun batch(id: Long, vararg squads: SquadParticipant, order: TacticalOrder = TacticalOrder.ATTACK, at: Long = 0) = CommandBatch(UUID(1, id), principal, order, objective, squads.toList(), at, entry)
    private fun started(result: OperationCommandResult) = (result as OperationCommandResult.Started).operation
    private fun registry() = OperationRegistry({ a, b -> a == b || setOf(a, b) == setOf("a", "ally") }, { _, _ -> true })

    @Test fun `common command creates one operation and rejects incompatible selection atomically`() {
        val registry = registry()
        val old = started(registry.command(batch(1, squad(10))))
        assertEquals(OperationCommandResult.Rejected("incompatible_factions"), registry.command(batch(2, squad(10), squad(11, "enemy"))))
        assertEquals(old.id, registry.operationOf(squad(10).id)!!.id)
        assertNull(registry.operationOf(squad(11).id))
        val group = started(registry.command(batch(3, squad(10), squad(11, "ally"))))
        assertEquals(2, group.members.size)
        assertEquals(group.id, registry.operationOf(squad(11).id)!!.id)
        assertEquals(OperationStatus.CANCELLED, registry.operation(old.id)!!.status)
    }

    @Test fun `reinforcement contributes no membership before fresh safe physical arrival`() {
        val registry = registry()
        val operation = started(registry.command(batch(1, squad(10), squad(11))))
        val pending = registry.command(batch(2, squad(12), at = 20)) as OperationCommandResult.Reinforcement
        assertEquals(operation.id, pending.pending.operation)
        assertEquals(2, registry.operation(operation.id)!!.members.size)
        assertNull(registry.operationOf(squad(12).id))
        val arrival = ArrivalReport(squad(12).id, 1, operation.id, 21, entry.center, true, true)
        assertFalse(registry.arrive(arrival.copy(position = Point3(0.0, 64.0, 0.0)), 21))
        assertFalse(registry.arrive(arrival.copy(position = Point3(90.0, 80.0, 100.0)), 21))
        assertFalse(registry.arrive(arrival.copy(safe = false), 21))
        assertFalse(registry.arrive(arrival.copy(combatReady = false), 21))
        assertFalse(registry.arrive(arrival.copy(orderVersion = 0), 21))
        assertFalse(registry.arrive(arrival, 40))
        assertFalse(registry.arrive(arrival.copy(observedAt = 22), 21))
        assertTrue(registry.arrive(arrival, 21))
        assertEquals(3, registry.operation(operation.id)!!.members.size)
        assertNull(registry.pending(squad(12).id))
    }

    @Test fun `new order removes reinforcement and ended operation cannot absorb late command`() {
        val registry = registry()
        val operation = started(registry.command(batch(1, squad(10), squad(11))))
        registry.command(batch(2, squad(12), at = 20))
        val defense = started(registry.command(batch(3, squad(12), order = TacticalOrder.DEFEND, at = 21)))
        assertNull(registry.pending(squad(12).id))
        assertFalse(registry.arrive(ArrivalReport(squad(12).id, 1, operation.id, 22, entry.center, true, true), 22))
        assertEquals(defense.id, registry.operationOf(squad(12).id)!!.id)
        registry.finish(operation.id, OperationStatus.COMPLETED)
        val next = started(registry.command(batch(4, squad(13), at = 23)))
        assertNotEquals(operation.id, next.id)
        assertEquals(OperationStatus.COMPLETED, registry.operation(operation.id)!!.status)
    }

    @Test fun `another common command in same place is a distinct operation`() {
        val registry = registry()
        val first = started(registry.command(batch(1, squad(10), squad(11))))
        val second = started(registry.command(batch(2, squad(12), squad(13))))
        assertNotEquals(first.id, second.id)
        assertEquals(2, registry.operation(first.id)!!.members.size)
    }

    @Test fun `ownership capability and dimension are separate atomic validations`() {
        val registry = OperationRegistry({ _, _ -> true }, { participant, _ -> participant.id != squad(12).id })
        assertEquals(OperationCommandResult.Rejected("no_command_authority"), registry.command(batch(1, squad(10), squad(11).copy(principal = UUID(0, 2)))))
        assertEquals(OperationCommandResult.Rejected("unsupported_order"), registry.command(batch(2, squad(10), squad(12))))
        assertEquals(OperationCommandResult.Rejected("wrong_dimension"), registry.command(batch(3, squad(10), squad(11).copy(dimension = "other"))))
        assertNull(registry.operationOf(squad(10).id))
    }

    @Test fun `diplomacy change invalidates pending arrival without disturbing other members`() {
        var allied = true
        val registry = OperationRegistry({ a, b -> a == b || allied }, { _, _ -> true })
        val operation = started(registry.command(batch(1, squad(10), squad(11))))
        registry.command(batch(2, squad(12, "ally"), at = 20))
        allied = false
        assertFalse(registry.arrive(ArrivalReport(squad(12).id, 1, operation.id, 21, entry.center, true, true), 21))
        assertEquals(2, registry.operation(operation.id)!!.members.size)
    }
}
