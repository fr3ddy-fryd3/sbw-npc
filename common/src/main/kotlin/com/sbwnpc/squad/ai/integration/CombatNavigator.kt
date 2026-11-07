package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.CombatPathRetry
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/**
 * One man's walking in a fight: every point and path he takes keeps the enemy clearance that
 * coordinated squad routes keep, and a path refused for a target is not searched again at once.
 *
 * It owns the retry memory and the path a spot search already found ([select]), so the search's
 * A* is not paid twice for the same spot.
 */
class CombatNavigator {
    /** The current plan constrains destinations and every path node with the same predicate. */
    var permits: (Vec3) -> Boolean = { true }
    private val retry = CombatPathRetry()
    private var selected: Path? = null

    /** Hands over the path a spot search found to reach the spot it chose. */
    fun select(path: Path) {
        selected = path
    }

    /** Forgets the found path and the refused requests: a new fight, order or weapon. */
    fun reset() {
        selected = null
        retry.reset()
    }

    /** Forgets only the found path: the search it came from was dropped. */
    fun forgetSelection() {
        selected = null
    }

    /** Whether standing at [point] keeps the enemy clearance from [target] and the squad's safe distance. */
    fun safePosition(entity: NpcEntity, target: LivingEntity, point: Vec3): Boolean {
        val view = entity.currentSquad()?.tactics?.snapshot
        return permits(point) && CombatPositionSafety.permits(entity.position(), point, target.position(), CombatPositionSafety.clearance(entity.npcClass)) &&
            (view == null || TacticalPositions.safeDistance(entity, view, point))
    }

    /** Whether walking [path] keeps the enemy clearance from [target] all the way. */
    fun safePath(entity: NpcEntity, target: LivingEntity, path: Path): Boolean {
        if ((1 until path.nodeCount).any { !permits(path.getNodePos(it).let { p -> Vec3.atBottomCenterOf(p) }) }) return false
        val view = entity.currentSquad()?.tactics?.snapshot
        if (view != null && !TacticalPositions.safePath(entity, view, path)) return false
        return CombatPositionSafety.permitsPath(entity.position(), path, listOf(target.position()), CombatPositionSafety.clearance(entity.npcClass))
    }

    /**
     * Walks to [point] by a safe path: keeps the current one if it still gets there, takes the
     * selected one if it reaches the point, else searches. False when the point or every path is
     * unsafe, or the search is refused for now.
     */
    fun navigate(entity: NpcEntity, target: LivingEntity, point: Vec3): Boolean {
        if (keepsCurrent(entity, target, point)) return true
        // Validate cheap geometry before paying for A*. A completed post needs no new path.
        if (!safePosition(entity, target, point)) {
            entity.navigation.stop()
            return false
        }
        if (FiringPostArrival.reached(entity.position(), point)) {
            entity.navigation.stop()
            retry.reset()
            return true
        }
        val found = selected
        selected = null
        if (found != null && TacticalPositions.reaches(found, point) && safePath(entity, target, found)) {
            retry.reset()
            return entity.navigation.moveTo(found, 1.0)
        }
        return search(entity, target, point)
    }

    /** Whether the path he walks still gets to [point] safely; an unsafe one is stopped. */
    private fun keepsCurrent(entity: NpcEntity, target: LivingEntity, point: Vec3): Boolean {
        val current = entity.navigation.path?.takeIf { !it.isDone } ?: return false
        if (!safePath(entity, target, current)) {
            entity.navigation.stop()
            return false
        }
        return TacticalPositions.reaches(current, point)
    }

    /** A new A* to [point], unless the same request was refused lately or the budget is spent. */
    private fun search(entity: NpcEntity, target: LivingEntity, point: Vec3): Boolean {
        val request = CombatPathRetry.Request(entity.position(), point, target.uuid, target.position())
        if (!retry.permits(request, entity.level().gameTime)) return false
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false || !TacticalBudget.path(entity)) return false
        val path = TacticalPositions.pathToPosition(entity, point)
        if (!TacticalPositions.reaches(path, point) || !safePath(entity, target, path!!)) {
            retry.reject(request, entity.level().gameTime)
            entity.navigation.stop()
            return false
        }
        retry.reset()
        return entity.navigation.moveTo(path, 1.0)
    }
}
