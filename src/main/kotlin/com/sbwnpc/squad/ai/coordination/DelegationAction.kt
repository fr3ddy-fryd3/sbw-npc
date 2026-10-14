package com.sbwnpc.squad.ai.coordination

import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionKey
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.ExecutionTicket
import com.sbwnpc.squad.ai.planning.TaskCall

data class DelegationTask(val child: AgentId, val task: TaskCall<*>)

/** A parent delegates intent. Child observations and execution remain owned by the child. */
class DelegationAction(private val parent: ExecutionTicket, private val task: DelegationTask, private val ledger: AssignmentLedger) : ActionExecution {
    private var assignment: AssignmentTicket? = null
    override fun start(now: Long): ActionUpdate =
        when (val result = ledger.delegate(parent, task.child, task.task, now)) {
            is DelegationResult.Assigned -> {
                assignment = result.assignment.ticket
                update()
            }

            is DelegationResult.Rejected -> ActionUpdate.Failed(result.reason)
        }

    override fun tick(now: Long): ActionUpdate = update()
    private fun update(): ActionUpdate {
        val current = assignment?.let(ledger::view) ?: return ActionUpdate.Failed("assignment_missing")
        return when (current.status) {
            AssignmentStatus.SUCCEEDED -> ActionUpdate.Completed(current.report!!.evidence)
            AssignmentStatus.FAILED -> ActionUpdate.Failed(current.report?.reason ?: "child_failed")
            AssignmentStatus.REVOKED -> ActionUpdate.Failed("assignment_revoked")
            AssignmentStatus.WAITING -> ActionUpdate.Deferred(current.report?.reason ?: "child_waiting")
            AssignmentStatus.OFFERED, AssignmentStatus.ACTIVE -> ActionUpdate.Running
        }
    }
    override fun suspend(now: Long, reason: String) {
        assignment?.let { ledger.hold(it, now, reason) }
    }
    override fun resume(now: Long) {
        assignment?.let { ledger.hold(it, now, null) }
    }
    override fun cancel(now: Long, reason: String) {
        assignment?.let { ledger.revoke(it, now, reason) }
    }
    companion object {
        val KEY = ActionKey("delegate", DelegationTask::class.java)
    }
}
