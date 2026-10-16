package com.sbwnpc.squad.combat.tactics.operation

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Bounded native validation of a new approach for a stopped lane; never changes player orders. */
internal class AttackRouteRecovery {
    private var nextPathAt = Long.MIN_VALUE
    private var cursor = 0

    fun update(level: ServerLevel, operations: List<AttackOperation>, forces: List<AttackForce>, squads: Map<UUID, Squad>) {
        val now = level.gameTime
        if (now < nextPathAt) return
        val pending = operations.filter { it.state.stage != AttackStage.COMPLETED }.flatMap { operation ->
            operation.lanes.values.filter { it.blocked && it.role != AttackRole.RESERVE && now >= it.retry.nextAt }.map { operation to it }
        }
        if (pending.isEmpty()) return
        val (operation, lane) = pending[Math.floorMod(cursor++, pending.size)]
        val force = forces.firstOrNull { it.id == lane.squad && it.stamp == lane.stamp } ?: return
        if (force.fighters.any { it.suppressed }) return
        if (force.underFire && forces.none { it.id != force.id && it.id in operation.lanes && it.covering >= 2 }) return
        val view = squads[lane.squad]?.tactics?.snapshot ?: return
        val actor = force.fighters.mapNotNull { level.getEntity(it.id) as? NpcEntity }
            .filter { !it.busyWithRole() && !it.resupplying && !it.movementLockedByCover() && !it.combatLockedByMedic() && !it.evadingGrenade() && (it.navigation as? VehicleAwareNavigation)?.canPlan == true }
            .minByOrNull { it.position().distanceToSqr(force.center) } ?: return
        val candidate = lane.retry.candidate(force.center, now) ?: return
        val goal = Terrain.feetAt(level, candidate, 12) { point -> level.noCollision(actor, actor.getDimensions(actor.pose).makeBoundingBox(point)) }
        if (goal == null || goal.subtract(force.center).horizontalDistance() < 2.0 || !lane.corridor.permits(force.center, goal)) {
            lane.retry.rejected(now)
            return
        }
        if (!TacticalBudget.path(actor)) return
        nextPathAt = now + 20 // One extra native probe per world per second, within the shared budget.
        val path = TacticalPositions.pathToPosition(actor, goal)
        if (!TacticalPositions.reaches(path, goal) || !TacticalPositions.safePath(actor, view, path!!)) {
            lane.retry.rejected(now)
            DebugFlags.log(LogGroup.ORDER, "[operation] id={} squad={} event=recovery_probe_rejected point={} round={} nextAt={}", operation.id, lane.squad, goal, lane.retry.rounds, lane.retry.nextAt)
            return
        }
        // Keep the actual native bend inside the corridor, including a detour around a wall.
        // Squad arrival is still measured by deploy(), not by this representative actor's path.
        val corridor = listOf(force.center) + (0 until path!!.nodeCount).map { index ->
            val node = path.getNode(index)
            Vec3(node.x + 0.5, node.y.toDouble(), node.z + 0.5)
        } + listOf(goal, lane.retry.target!!)
        operation.recover(lane, force.center, goal, corridor.distinct(), now)
    }
}
