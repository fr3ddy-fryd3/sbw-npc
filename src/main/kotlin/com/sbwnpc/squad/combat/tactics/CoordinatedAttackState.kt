package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.tactics.operation.AttackAssignment
import com.sbwnpc.squad.combat.tactics.operation.AttackRole
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Executes one operation lane through the existing covered-wave and native navigation machinery. */
class CoordinatedAttackState : CoveredManeuverState() {
    override val stability = TacticalStability(committed = true, quickReplacement = true, phaseLimit = 480, expires = false)
    override val movementTimeout = 320L
    override val assignmentRange = 256.0
    override val positions = CoordinatedAttackPositions
    override fun usesAttackFront(member: TacticalMember) = false
    override fun guardsPatient() = true
    private var movingHalf = 0
    private val groups = LinkedHashMap<UUID, Int>()
    private val offsets = LinkedHashMap<UUID, Double>()
    private val reserveCells = LinkedHashMap<UUID, Int>()
    private val continuing = HashSet<UUID>()
    private var assignmentKey: Pair<Long, Int>? = null
    private var underFireLastTick: Boolean? = null

    override fun requiresActualCover(context: TacticalContext) = context.view.incoming.isNotEmpty() || context.view.members.any { it.suppressed }
    override fun externalCover(context: TacticalContext) = context.view.attackAssignment?.externalCover == true
    override fun shouldPauseForLostCover(context: TacticalContext) = requiresActualCover(context)
    override fun searchFailureRetry(task: TacticalTask, rejections: Map<String, Int>): Long? = if (task.job in TacticalRoutes.TRANSIT_JOBS && rejections.isNotEmpty() && rejections.keys.all { it in setOf("occupied_or_hazard", "occupied_on_review") }) 40L else null

    override fun prepareAssignments(layout: TacticalLayout) {
        val assignment = layout.view.attackAssignment ?: return
        offsets.clear()
        offsets.putAll(assignment.slots)
        if (assignment.role == AttackRole.RESERVE) {
            reserveCells.keys.retainAll(layout.view.members.map { it.id }.toSet())
            for (member in layout.view.members) {
                if (member.id !in reserveCells) reserveCells[member.id] = (0 until layout.view.members.size).first { it !in reserveCells.values }
            }
        }
        for ((index, member) in layout.fighters.withIndex()) groups.putIfAbsent(member.id, index % 2)
        // New reinforcements take vacant edge slots; casualties do not renumber survivors.
        for (member in layout.fighters.filter { it.id !in offsets }) {
            offsets[member.id] = (offsets.values.maxOrNull() ?: 0.0) + SquadFormation.COMBAT_SPACING
        }
    }

    override fun reconcileHoldingAssignments(context: TacticalContext) {
        prepareAssignments(TacticalLayout(context))
        super.reconcileHoldingAssignments(context)
    }

    override fun beforeTick(context: TacticalContext) {
        val assignment = context.view.attackAssignment
        if (assignment == null) {
            context.plan.complete(context.view.now, "operation_released")
            return
        }
        val key = assignment.operation to assignment.revision
        val underFire = requiresActualCover(context)
        val pressureChanged = underFireLastTick != null && underFireLastTick != underFire
        underFireLastTick = underFire
        if (assignmentKey != key) {
            assignmentKey = key
            continuing.clear()
            assign(context, trigger = "operation_lane_updated")
        } else if (pressureChanged) {
            // Switching from quiet travel to protected bounds cancels old open-ground
            // proposals; native release defers interruption until a jumping NPC lands.
            continuing.clear()
            assign(context, trigger = "operation_incoming_fire_changed")
        }
        // Holding members can keep an actual protected firing post; the wave protocol still
        // owns movers and all cover/medical/supply behavior priorities remain in force.
        super.beforeTick(context)
    }

    internal override fun prepare(context: TacticalContext) {
        val assignment = context.view.attackAssignment ?: return
        if (assignment.holding || assignment.deployed) {
            phases.transition(ExecutingPhase(), context, "operation_sector_holding")
        } else if (!requiresActualCover(context) || externalCover(context)) {
            phases.transition(ExecutingPhase(), context, if (externalCover(context)) "operation_actual_cover" else "operation_quiet_bound")
        } else {
            super.prepare(context)
        }
    }

    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val assignment = layout.view.attackAssignment ?: return layout.cover(member)
        val previous = layout.plan.tasks[member.id]
        if (member.id in continuing && previous?.job == TacticalJob.ADVANCE && !TacticalEvidence.settled(member, previous)) return previous
        continuing.remove(member.id)
        if (assignment.role == AttackRole.RESERVE) {
            val cell = SquadFormation.gridOffset(reserveCells[member.id] ?: index, layout.view.members.size).scale(1.5)
            val rally = assignment.sector.add(side(assignment.facing).scale(cell.x)).add(assignment.facing.scale(cell.z))
            // The reserve clears the approach corridor before holding. Requiring a firing
            // lane here leaves it stationary behind the center and in front of a moving wing.
            return layout.intent(TacticalJob.RESERVE, rally)
        }
        val offset = side(assignment.facing).scale(offsets[member.id] ?: 0.0)
        val sector = assignment.sector.add(offset)
        if (assignment.holding || assignment.deployed) {
            return layout.intent(TacticalJob.COVER, if (assignment.blocked) member.position else sector).also {
                if (!assignment.blocked) it.frontage = TacticalFrontPost(sector, assignment.facing)
            }
        }
        val underFire = layout.view.incoming.isNotEmpty() || layout.view.members.any { it.suppressed }
        if ((underFire && groups[member.id] != movingHalf) || layout.plan.status == TacticalStatus.PREPARING) return layout.cover(member)
        val destination = assignment.waypoint.add(offset)
        if (member.position.subtract(destination).horizontalDistance() <= 3.0) return layout.cover(member)
        var anchor = TacticalLayout.towards(member.position, destination, if (underFire) 12.0 else 24.0)
        anchor = TacticalClearance.threats(layout.view).fold(anchor) { point, threat ->
            CombatPositionSafety.approach(member.position, point, threat, CombatPositionSafety.clearance(member.role) + CombatPositionSafety.APPROACH_MARGIN)
        }
        if (anchor.distanceTo(destination) >= member.position.distanceTo(destination) - 0.5) return layout.cover(member)
        return layout.intent(TacticalJob.ADVANCE, anchor)
    }

    override fun arrived(context: TacticalContext, movers: List<TacticalMember>) {
        bounds++
        movingHalf = 1 - movingHalf
        phases.transition(PreparingPhase(), context, "operation_wave_arrived")
    }

    internal override fun execute(context: TacticalContext) {
        val movers = context.view.members.filter { context.plan.tasks[it.id]?.job == TacticalJob.ADVANCE }
        val settled = movers.filter { TacticalEvidence.settled(it, context.plan.tasks[it.id]) }
        val supported = !requiresActualCover(context) || externalCover(context) || context.view.members.any {
            context.plan.tasks[it.id]?.job in TacticalJob.FIRE_SUPPORT && maintainsCover(context, it)
        }
        if (supported && settled.isNotEmpty() && settled.size < movers.size && settled.size * 4 >= movers.size * 3) {
            exchangePartialWave(context, movers, settled)
        } else {
            super.execute(context)
        }
    }

    override fun movementTimedOut(context: TacticalContext, movers: List<TacticalMember>) {
        val settled = movers.filter { TacticalEvidence.settled(it, context.plan.tasks[it.id]) }
        if (settled.isEmpty()) {
            super.movementTimedOut(context, movers)
            return
        }
        exchangePartialWave(context, movers, settled)
    }

    private fun exchangePartialWave(context: TacticalContext, movers: List<TacticalMember>, settled: List<TacticalMember>) {
        // A crowded straggler must not reset the whole squad or lose its pending native
        // route. Exchange the other half's duty and carry unfinished tasks through the wave.
        for (member in movers - settled.toSet()) {
            continuing += member.id
            groups[member.id] = 1 - movingHalf
        }
        bounds++
        movingHalf = 1 - movingHalf
        phases.transition(PreparingPhase(), context, "operation_partial_wave_continues_routes")
    }

    override fun noMovers(context: TacticalContext) {
        val assignment = context.view.attackAssignment ?: return
        if (assignment.holding || assignment.deployed) return
        movingHalf = 1 - movingHalf
        phases.transition(PreparingPhase(), context, "operation_other_wave")
    }

    private fun side(forward: Vec3) = Vec3(-forward.z, 0.0, forward.x)
}

object CoordinatedAttackPositions : TacticalPositionPolicy {
    override fun readyBatch(task: TacticalTask, anchor: Vec3, scored: Map<Vec3, TacticalPositionScore>): Boolean = task.job in TacticalRoutes.TRANSIT_JOBS && scored.any { (point, score) -> score.purpose == TacticalPositionScore.Purpose.COVERED_TRANSIT && point.distanceTo(anchor) <= 2.0 }
    override fun additionalProbes(view: TacticalSnapshot, task: TacticalTask, origin: Vec3, role: NpcClass): List<Vec3> = if (task.job in TacticalRoutes.TRANSIT_JOBS) TacticalRoutes.flankProbes(origin, task, view, role) else emptyList()
    override fun requiresShelter(view: TacticalSnapshot, task: TacticalTask) = BoundingPositions.requiresShelter(view, task)
    override fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3): Boolean = view.attackAssignment?.let { it.holding || it.corridor.permits(view.members.firstOrNull { member -> member.id == task.operationMember }?.position ?: view.center, point) } != false
    override fun route(view: TacticalSnapshot, task: TacticalTask, origin: Vec3, point: Vec3): Boolean = view.attackAssignment?.let { it.holding || it.corridor.permitsRoute(origin, point) } != false
}
