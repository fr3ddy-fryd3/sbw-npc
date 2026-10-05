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
        val detail = TacticalEventFormat.detail(event)
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
