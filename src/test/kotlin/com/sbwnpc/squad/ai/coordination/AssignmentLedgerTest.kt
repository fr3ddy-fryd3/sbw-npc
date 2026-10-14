package com.sbwnpc.squad.ai.coordination

import com.sbwnpc.squad.ai.planning.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AssignmentLedgerTest {
    private val goal = TaskKey("goal", String::class.java)
    private val session = UUID(0, 7)
    private fun agent(level: AgentLevel, id: Long) = AgentId(level, UUID(0, id))
    private fun execution(owner: PlanOwner, identity: String = "task") = ExecutionTicket(owner, identity, 1, session)
    private val operation = execution(PlanOwner(agent(AgentLevel.OPERATION, 1), UUID(0, 10)))
    private val squad = agent(AgentLevel.SQUAD, 2)
    private fun assigned(result: DelegationResult) = (result as DelegationResult.Assigned).assignment

    @Test fun `identical delegation is stable but changed task rejects old feedback`() {
        val ledger = AssignmentLedger({ true }, { _, _ -> true })
        val first = assigned(ledger.delegate(operation, squad, goal.call("left"), 0))
        assertEquals(first.ticket, assigned(ledger.delegate(operation, squad, goal.call("left"), 1)).ticket)
        val second = assigned(ledger.delegate(operation, squad, goal.call("right"), 2))
        assertNotEquals(first.ticket, second.ticket)
        assertEquals(AssignmentStatus.REVOKED, ledger.view(first.ticket)!!.status)
        assertFalse(ledger.report(ChildReport(first.ticket, 1, 3, AssignmentStatus.SUCCEEDED, listOf(ActionEvidence("arrived", 3))), 3))
        assertEquals(AssignmentStatus.OFFERED, ledger.view(second.ticket)!!.status)
    }

    @Test fun `hierarchy revokes obsolete descendants while independent squad continues`() {
        val events = ArrayList<AssignmentEvent>()
        val ledger = AssignmentLedger({ true }, { _, _ -> true }, events::add)
        val left = assigned(ledger.delegate(operation, squad, goal.call("left"), 0))
        val right = assigned(ledger.delegate(operation, agent(AgentLevel.SQUAD, 3), goal.call("right"), 0))
        val team = assigned(ledger.delegate(execution(left.ticket.owner), agent(AgentLevel.TEAM, 4), goal.call("bound"), 1))
        val npc = assigned(ledger.delegate(execution(team.ticket.owner), agent(AgentLevel.NPC, 5), goal.call("move"), 2))
        assertTrue(ledger.revoke(left.ticket, 3, "flank_reassigned"))
        assertEquals(AssignmentStatus.REVOKED, ledger.view(team.ticket)!!.status)
        assertEquals(AssignmentStatus.REVOKED, ledger.view(npc.ticket)!!.status)
        assertEquals(AssignmentStatus.OFFERED, ledger.view(right.ticket)!!.status)
        assertEquals(3, events.count { it.reason == "flank_reassigned" })
        assertTrue(ledger.forget(npc.ticket))
        assertNull(ledger.view(npc.ticket))
        assertFalse(ledger.report(ChildReport(npc.ticket, 1, 4, AssignmentStatus.ACTIVE), 4))
    }

    @Test fun `alliance does not grant authority and levels cannot be skipped`() {
        val ledger = AssignmentLedger({ true }, { _, _ -> false })
        assertEquals(DelegationResult.Rejected("no_command_authority"), ledger.delegate(operation, squad, goal.call("move"), 0))
        assertEquals(DelegationResult.Rejected("invalid_hierarchy_edge"), ledger.delegate(operation, agent(AgentLevel.NPC, 8), goal.call("move"), 0))
        val expired = AssignmentLedger({ false }, { _, _ -> true })
        assertEquals(DelegationResult.Rejected("parent_execution_expired"), expired.delegate(operation, squad, goal.call("move"), 0))
    }

    @Test fun `two live parents cannot own one child and expired owner can be replaced`() {
        val live = hashSetOf(operation)
        val ledger = AssignmentLedger(live::contains, { _, _ -> true })
        val other = operation.copy(identity = "other")
        live += other
        val old = assigned(ledger.delegate(operation, squad, goal.call("left"), 0))
        assertEquals(DelegationResult.Rejected("child_already_assigned"), ledger.delegate(other, squad, goal.call("right"), 1))
        live -= operation
        val replacement = assigned(ledger.delegate(other, squad, goal.call("right"), 2))
        assertNotEquals(old.ticket, replacement.ticket)
        assertFalse(ledger.report(ChildReport(old.ticket, 1, 2, AssignmentStatus.ACTIVE), 2))
    }

    @Test fun `reports require ordered observed feedback and completion evidence`() {
        val ledger = AssignmentLedger({ true }, { _, _ -> true })
        val assignment = assigned(ledger.delegate(operation, squad, goal.call("move"), 10))
        val active = ChildReport(assignment.ticket, 1, 11, AssignmentStatus.ACTIVE)
        assertFalse(ledger.report(active, 10))
        assertTrue(ledger.report(active, 11))
        assertFalse(ledger.report(active, 11))
        assertFalse(ledger.report(active.copy(sequence = 2, observedAt = 10), 11))
        assertFalse(ledger.report(active.copy(sequence = 2, status = AssignmentStatus.SUCCEEDED), 11))
        val done = active.copy(sequence = 2, observedAt = 12, status = AssignmentStatus.SUCCEEDED, evidence = listOf(ActionEvidence("held_goal", 12)))
        assertFalse(ledger.report(done.copy(evidence = listOf(ActionEvidence("future", 13))), 12))
        assertTrue(ledger.report(done, 12))
        assertFalse(ledger.report(active.copy(sequence = 3, observedAt = 13), 13))
        assertEquals(AssignmentStatus.SUCCEEDED, ledger.view(assignment.ticket)!!.status)
    }

    @Test fun `revised goal preserves owner but rejects completion of previous revision`() {
        val ledger = AssignmentLedger({ true }, { _, _ -> true })
        val assignment = assigned(ledger.delegate(operation, squad, goal.call("old"), 0))
        assertTrue(ledger.report(ChildReport(assignment.ticket, 1, 1, AssignmentStatus.ACTIVE), 1))
        assertTrue(ledger.revise(assignment.ticket, goal.call("new"), 2))
        val updated = ledger.view(assignment.ticket)!!
        assertEquals(assignment.ticket.owner, updated.ticket.owner)
        assertEquals(1, updated.revision)
        val oldReply = ChildReport(assignment.ticket, 2, 3, AssignmentStatus.SUCCEEDED, listOf(ActionEvidence("old_goal", 3)))
        assertFalse(ledger.report(oldReply, 3))
        assertFalse(ledger.report(oldReply.copy(revision = 1, observedAt = 1), 3))
        assertTrue(ledger.report(oldReply.copy(revision = 1, evidence = listOf(ActionEvidence("new_goal", 3))), 3))
    }

    @Test fun `revoked command authority permits moving only the affected child to new team`() {
        val other = operation.copy(identity = "other")
        var allowedOld = true
        val ledger = AssignmentLedger({ true }, { parent, _ -> parent != operation.owner.agent || allowedOld })
        val first = assigned(ledger.delegate(operation, squad, goal.call("old"), 0))
        allowedOld = false
        // Both tickets use the same agent, so use an independent parent identity for the new authority.
        val different = other.copy(owner = other.owner.copy(agent = agent(AgentLevel.OPERATION, 30)))
        val second = assigned(ledger.delegate(different, squad, goal.call("new"), 1))
        assertNotEquals(first.ticket, second.ticket)
        assertEquals(AssignmentStatus.REVOKED, ledger.view(first.ticket)!!.status)
    }

    @Test fun `continuous post success remains owned and can be lost or revised`() {
        val ledger = AssignmentLedger({ true }, { _, _ -> true })
        val assignment = assigned(ledger.delegate(operation, squad, goal.call("post"), 0, continuous = true))
        assertTrue(ledger.report(ChildReport(assignment.ticket, 1, 1, AssignmentStatus.SUCCEEDED, listOf(ActionEvidence("arrived", 1))), 1))
        assertEquals(DelegationResult.Rejected("child_already_assigned"), ledger.delegate(operation.copy(identity = "other"), squad, goal.call("steal"), 2))
        assertTrue(ledger.report(ChildReport(assignment.ticket, 2, 2, AssignmentStatus.ACTIVE), 2))
        assertTrue(ledger.revise(assignment.ticket, goal.call("next_post"), 3))
        assertEquals(assignment.ticket.owner, ledger.view(assignment.ticket)!!.ticket.owner)
        assertTrue(ledger.revoke(assignment.ticket, 4, "new_order"))
        assertFalse(ledger.report(ChildReport(assignment.ticket, 3, 5, AssignmentStatus.ACTIVE, revision = 1), 5))
    }
}
