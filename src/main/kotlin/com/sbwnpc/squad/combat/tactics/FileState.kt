package com.sbwnpc.squad.combat.tactics

import java.util.UUID

class FileState : CoveredManeuverState() {
    override val positions = FilePositions
    private val passed = HashSet<UUID>()
    private var cover: UUID? = null
    private var movers = emptySet<UUID>()
    override fun guardsPatient() = false

    override fun prepareAssignments(layout: TacticalLayout) {
        val pending = layout.fighters.sortedBy { it.position.distanceToSqr(layout.focus) }.filter { it.id !in passed }
        movers = pending.take(2).map { it.id }.toSet()
        cover = if (layout.view.visible.isNotEmpty()) pending.lastOrNull()?.id else null
    }

    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val job = when {
            member.id == cover -> TacticalJob.COVER
            member.id in passed -> TacticalJob.OBSERVE
            member.id in movers && (layout.view.visible.isEmpty() || layout.plan.status == TacticalStatus.EXECUTING) -> TacticalJob.ADVANCE
            else -> TacticalJob.WAIT
        }
        val anchor = when {
            member.id in passed -> TacticalLayout.towards(member.position, layout.focus, 8.0).add(layout.side.scale(if (index % 2 == 0) 4.0 else -4.0))
            job == TacticalJob.ADVANCE -> TacticalLayout.towards(member.position, layout.focus, 10.0)
            else -> member.position
        }
        return layout.intent(job, anchor)
    }

    override fun prepare(context: TacticalContext) {
        if (context.view.visible.isEmpty()) phases.transition(ExecutingPhase(), context, "unopposed_crossing")
        else super.prepare(context)
    }

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        passed.addAll(movers.map { it.id })
        val done = passed.size >= context.view.fighting.count { it.role != com.sbwnpc.squad.npc.NpcClass.MEDIC }
        phases.transition(if (done) CompletedPhase() else ExecutingPhase(), context, if (done) "file_complete" else "file_pair_arrived")
    }

    override fun phaseEntered(context: TacticalContext, from: TacticalPhase?) {
        if (from == TacticalPhase.EXECUTING) assign(context, trigger = "file_next_pair")
        else super.phaseEntered(context, from)
    }
}
