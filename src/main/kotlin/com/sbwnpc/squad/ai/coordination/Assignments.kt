package com.sbwnpc.squad.ai.coordination

import com.sbwnpc.squad.ai.planning.ActionEvidence
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.ExecutionTicket
import com.sbwnpc.squad.ai.planning.PlanOwner
import com.sbwnpc.squad.ai.planning.TaskCall
import java.util.UUID

data class AssignmentTicket(val id: UUID, val parent: ExecutionTicket, val child: AgentId) {
    val owner get() = PlanOwner(child, id)
}

enum class AssignmentStatus { OFFERED, ACTIVE, WAITING, SUCCEEDED, FAILED, REVOKED }
data class ChildReport(val ticket: AssignmentTicket, val sequence: Long, val observedAt: Long, val status: AssignmentStatus, val evidence: List<ActionEvidence> = emptyList(), val reason: String? = null)
data class AssignmentView(val ticket: AssignmentTicket, val task: TaskCall<*>, val issuedAt: Long, val status: AssignmentStatus, val report: ChildReport?)
data class AssignmentEvent(val assignment: AssignmentView, val reason: String)
sealed interface DelegationResult {
    data class Assigned(val assignment: AssignmentView) : DelegationResult
    data class Rejected(val reason: String) : DelegationResult
}

/** Main-thread protocol; authority is supplied separately from faction relationships. */
class AssignmentLedger(
    private val live: (ExecutionTicket) -> Boolean,
    private val authorized: (AgentId, AgentId) -> Boolean,
    private val event: (AssignmentEvent) -> Unit = {},
) {
    private val assignments = LinkedHashMap<UUID, AssignmentView>()
    private val byChild = HashMap<AgentId, UUID>()
    private val byParent = HashMap<ExecutionTicket, MutableSet<UUID>>()
    private val byOwner = HashMap<PlanOwner, MutableSet<ExecutionTicket>>()
    private val terminal = setOf(AssignmentStatus.SUCCEEDED, AssignmentStatus.FAILED, AssignmentStatus.REVOKED)

    fun delegate(parent: ExecutionTicket, child: AgentId, task: TaskCall<*>, now: Long): DelegationResult {
        if (!live(parent)) return DelegationResult.Rejected("parent_execution_expired")
        if (child.level.ordinal != parent.owner.agent.level.ordinal + 1) return DelegationResult.Rejected("invalid_hierarchy_edge")
        if (!authorized(parent.owner.agent, child)) return DelegationResult.Rejected("no_command_authority")
        val previous = byChild[child]?.let(assignments::getValue)
        if (previous != null && previous.ticket.parent == parent && previous.task == task && previous.status != AssignmentStatus.REVOKED) return DelegationResult.Assigned(previous)
        if (previous != null && previous.ticket.parent != parent && previous.status !in terminal && live(previous.ticket.parent)) return DelegationResult.Rejected("child_already_assigned")
        if (previous != null) revoke(previous.ticket, now, "assignment_replaced")
        val ticket = AssignmentTicket(UUID.randomUUID(), parent, child)
        val assignment = AssignmentView(ticket, task, now, AssignmentStatus.OFFERED, null)
        assignments[ticket.id] = assignment
        byChild[child] = ticket.id
        byParent.getOrPut(parent, ::LinkedHashSet).add(ticket.id)
        byOwner.getOrPut(parent.owner, ::LinkedHashSet).add(parent)
        event(AssignmentEvent(assignment, "assigned"))
        return DelegationResult.Assigned(assignment)
    }

    fun report(report: ChildReport, now: Long): Boolean {
        val current = assignments[report.ticket.id] ?: return false
        if (current.ticket != report.ticket || current.status in terminal || !live(current.ticket.parent)) return false
        if (report.sequence <= (current.report?.sequence ?: -1) || report.observedAt !in current.issuedAt..now) return false
        if (report.observedAt < (current.report?.observedAt ?: current.issuedAt)) return false
        if (report.status !in setOf(AssignmentStatus.ACTIVE, AssignmentStatus.WAITING, AssignmentStatus.SUCCEEDED, AssignmentStatus.FAILED)) return false
        if (report.evidence.any { it.observedAt > report.observedAt }) return false
        if (report.status == AssignmentStatus.SUCCEEDED && report.evidence.isEmpty()) return false
        val next = current.copy(status = report.status, report = report.copy(evidence = report.evidence.toList()))
        assignments[current.ticket.id] = next
        event(AssignmentEvent(next, "child_report"))
        return true
    }

    fun view(ticket: AssignmentTicket): AssignmentView? = assignments[ticket.id]?.takeIf { it.ticket == ticket }

    /** Revocation follows assignment owners, so an obsolete operation cannot leave live NPC tasks. */
    fun revoke(ticket: AssignmentTicket, now: Long, reason: String): Boolean {
        val current = assignments[ticket.id]?.takeIf { it.ticket == ticket } ?: return false
        if (current.status == AssignmentStatus.REVOKED) return false
        require(now >= current.issuedAt)
        val children = byOwner[ticket.owner]?.toList().orEmpty()
        children.forEach { cancel(it, now, reason) }
        val next = current.copy(status = AssignmentStatus.REVOKED)
        assignments[ticket.id] = next
        if (byChild[ticket.child] == ticket.id) byChild.remove(ticket.child)
        byParent[ticket.parent]?.let {
            it.remove(ticket.id)
            if (it.isEmpty()) {
                byParent.remove(ticket.parent)
                byOwner[ticket.parent.owner]?.let { owners ->
                    owners.remove(ticket.parent)
                    if (owners.isEmpty()) byOwner.remove(ticket.parent.owner)
                }
            }
        }
        event(AssignmentEvent(next, reason))
        return true
    }

    fun cancel(parent: ExecutionTicket, now: Long, reason: String) {
        byParent[parent]?.toList()?.forEach { assignments[it]?.let { assignment -> revoke(assignment.ticket, now, reason) } }
    }

    /** Bound history without losing live assignments or accepting a late reply to a removed ID. */
    fun forget(ticket: AssignmentTicket): Boolean {
        val current = view(ticket) ?: return false
        if (current.status != AssignmentStatus.REVOKED) return false
        assignments.remove(ticket.id)
        return true
    }
}
