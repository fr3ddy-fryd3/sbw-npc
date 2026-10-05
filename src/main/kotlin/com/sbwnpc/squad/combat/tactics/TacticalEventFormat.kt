package com.sbwnpc.squad.combat.tactics

/** Stable field names for tracing decisions, phase readiness and assignment lifecycle. */
object TacticalEventFormat {
    fun detail(event: TacticalEvent): String = when (event) {
        is TacticalEvent.PlanSelected -> "event=plan_selected pattern=${event.choice.pattern} reason=${event.choice.reason} previousPlan=${event.previous} previousPhase=${event.previousPhase} stamp=${event.orderStamp} trigger=${event.trigger} focus=${event.choice.focus}"
        is TacticalEvent.Lifecycle -> "event=${event.action} pattern=${event.pattern} statePhase=${event.phase} trigger=${event.trigger}"
        is TacticalEvent.Transition -> event.evidence.let {
            "event=transition from=${event.from} to=${event.to} trigger=${event.trigger} members=${it.members} visible=${it.visible} coverAssigned=${it.coverAssigned} coverReady=${it.coverReady} coverFired=${it.coverFired} movers=${it.movers} settledMovers=${it.settledMovers}"
        }
        is TacticalEvent.SelectionHeld -> "event=selection_held proposed=${event.proposed} active=${event.active} reason=${event.reason}"
        is TacticalEvent.TaskAssigned -> "event=task_assigned npc=${event.member} job=${event.job} anchor=${event.anchor} trigger=${event.trigger}"
        is TacticalEvent.TaskReleased -> "event=task_released npc=${event.member} job=${event.task.job} position=${event.task.position} trigger=${event.trigger}"
        is TacticalEvent.TaskPaused -> "event=task_${if (event.paused) "paused" else "resumed"} npc=${event.member} reason=${event.reason}"
        is TacticalEvent.MovementBlocked -> "event=movement_${if (event.current == null) "unblocked" else "blocked"} npc=${event.member} previous=${event.previous} reason=${event.current}"
    }
}
