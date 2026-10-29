package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.tactics.PositionPaths
import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** One bounded walk from shelter to a reachable launch column and back, using the shared path budget. */
internal class DroneLaunchWalk(entity: NpcEntity) {
    /** A preparation tick can still be searching, ready to launch, or unable to continue. */
    enum class Result { WAITING, READY, FAILED }

    private val origin = entity.position()
    private val orderStamp = entity.currentSquad()?.orderStamp
    private val started = entity.tickCount
    private val probes = (-SEARCH_RADIUS..SEARCH_RADIUS).flatMap { x ->
        (-SEARCH_RADIUS..SEARCH_RADIUS).filter { z -> x * x + z * z <= SEARCH_RADIUS * SEARCH_RADIUS }.map { z -> origin.add(x.toDouble(), 0.0, z.toDouble()) }
    }.sortedBy { it.distanceToSqr(origin) }
    private var cursor = 0
    private var ownedPath: Path? = null
    private var returningSince = 0

    /** Walks to a reachable clear candidate, retaining the cursor while shared path admission is deferred. */
    fun approach(entity: NpcEntity, level: ServerLevel, clear: (Vec3) -> Boolean): Result {
        if (!sameOrder(entity) || entity.tickCount - started >= WALK_TIMEOUT_TICKS) return Result.FAILED
        if (clear(entity.position())) return Result.READY
        if ((entity.navigation as? PositionPaths)?.canPlan == false) return Result.WAITING
        if (ownedPath != null && !entity.navigation.isDone) return Result.WAITING
        return find(entity, level, clear)
    }

    private fun find(entity: NpcEntity, level: ServerLevel, clear: (Vec3) -> Boolean): Result {
        var checked = 0
        while (cursor < probes.size && checked++ < PROBES_PER_TICK) {
            val point = candidate(entity, level, probes[cursor], clear)
            if (point == null) {
                cursor++
                continue
            }
            if (!TacticalBudget.path(entity)) return Result.WAITING
            cursor++
            val path = TacticalPositions.pathToPosition(entity, point)
            if (TacticalPositions.reaches(path, point)) {
                ownedPath = path
                entity.navigation.moveTo(path, WALK_SPEED)
            }
            // At most one native search per actor/tick, including rejected destinations.
            return Result.WAITING
        }
        return if (cursor == probes.size) Result.FAILED else Result.WAITING
    }

    private fun candidate(entity: NpcEntity, level: ServerLevel, raw: Vec3, clear: (Vec3) -> Boolean): Vec3? {
        val feet = Terrain.feetAt(level, raw, FLOOR_SEARCH) { at ->
            level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(at))
        } ?: return null
        return feet.takeIf { abs(it.y - origin.y) <= FLOOR_SEARCH && clear(it) }
    }

    /** The drone is airborne; only the route back to the original shelter remains. */
    fun beginReturn(entity: NpcEntity) {
        cancel(entity)
        returningSince = entity.tickCount
    }

    /** True when home, superseded by a new order/cover, or unable to return within the short errand. */
    fun returnHome(entity: NpcEntity): Boolean {
        if (returnInterrupted(entity) ||
            entity.tickCount - returningSince >= WALK_TIMEOUT_TICKS || entity.position().distanceToSqr(origin) <= AT_HOME_SQR
        ) {
            cancel(entity)
            return true
        }
        if (entity.navigation.path === ownedPath && !entity.navigation.isDone) return false
        if ((entity.navigation as? PositionPaths)?.canPlan == false || !TacticalBudget.path(entity)) return false
        val path = TacticalPositions.pathToPosition(entity, origin)
        if (!TacticalPositions.reaches(path, origin)) {
            cancel(entity)
            return true
        }
        ownedPath = path
        entity.navigation.moveTo(path, WALK_SPEED)
        return false
    }

    /** Never stop a path that cover or grenade evasion has replaced with its own. */
    fun cancel(entity: NpcEntity) {
        ownedPath?.let { if (entity.navigation.path === it) entity.navigation.stop() }
        ownedPath = null
    }

    private fun sameOrder(entity: NpcEntity) = entity.currentSquad()?.orderStamp == orderStamp

    private fun returnInterrupted(entity: NpcEntity) = !sameOrder(entity) || entity.holdsAny(NpcActivity.COVER, NpcActivity.EVADE, NpcActivity.PEEK, NpcActivity.HEAL)

    private companion object {
        const val SEARCH_RADIUS = 12
        const val FLOOR_SEARCH = 3
        const val PROBES_PER_TICK = 32
        const val WALK_TIMEOUT_TICKS = 200
        const val WALK_SPEED = 1.0
        const val AT_HOME_SQR = 1.0
    }
}
