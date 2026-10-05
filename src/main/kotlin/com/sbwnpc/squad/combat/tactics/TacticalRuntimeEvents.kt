package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel

/** Direct world subscriber: cleanup is unconditional, verbose event logging is build/config gated. */
class TacticalRuntimeEvents private constructor(private var squad: Squad, private var level: ServerLevel) : TacticalEventSink {
    override fun emit(event: TacticalEvent) {
        if (event is TacticalEvent.TaskReleased) {
            FiringSpots.release(event.member)
            val npc = level.getEntity(event.member) as? NpcEntity
            event.task.releaseNavigation(npc?.navigation?.path) { npc?.navigation?.stop() }
        }
        if (!DebugFlags.on(LogGroup.ORDER)) return
        val detail = when (event) {
            is TacticalEvent.PlanSelected -> "event=plan_selected pattern=${event.choice.pattern} reason=${event.choice.reason} previousPlan=${event.previous} previousPhase=${event.previousPhase} stamp=${event.orderStamp} trigger=${event.trigger} focus=${event.choice.focus}"
            is TacticalEvent.Lifecycle -> "event=${event.action} pattern=${event.pattern} statePhase=${event.phase} trigger=${event.trigger}"
            is TacticalEvent.Transition -> "event=transition from=${event.from} to=${event.to} trigger=${event.trigger} evidence=${event.evidence}"
            is TacticalEvent.SelectionHeld -> "event=selection_held proposed=${event.proposed} active=${event.active} reason=${event.reason}"
            is TacticalEvent.TaskAssigned -> "event=task_assigned npc=${event.member} job=${event.job} anchor=${event.anchor} trigger=${event.trigger}"
            is TacticalEvent.TaskReleased -> "event=task_released npc=${event.member} job=${event.task.job} position=${event.task.position} trigger=${event.trigger}"
            is TacticalEvent.TaskPaused -> "event=task_${if (event.paused) "paused" else "resumed"} npc=${event.member} reason=${event.reason}"
        }
        DebugFlags.log(LogGroup.ORDER, "[tactics] {} squad={} tick={} plan={} {}", squad.name, squad.id, event.tick, event.plan, detail)
    }

    companion object {
        fun bind(squad: Squad, level: ServerLevel) {
            val stream = squad.tactics.events
            val existing = stream.runtime as? TacticalRuntimeEvents
            if (existing == null) stream.runtime = TacticalRuntimeEvents(squad, level)
            else { existing.squad = squad; existing.level = level }
        }
    }
}
