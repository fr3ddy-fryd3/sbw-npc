package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Local positions are validated through the existing navigation and collider raycasts. */
object TacticalPositions {
    enum class Outcome { FOUND, DEFERRED, UNREACHABLE }
    data class Result(val outcome: Outcome, val position: Vec3? = null)

    fun find(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot): Result {
        val level = entity.level() as ServerLevel
        if (!TickBudget.hasRaycasts(level)) return Result(Outcome.DEFERRED)
        val taken = FiringSpots.nearbyWithBodies(level, entity, 40.0)
        val threats = view.visible.sortedByDescending { it.priority }.take(3)
        val hulls = Sightline.vehicleHulls(level, entity.boundingBox.inflate(48.0), entity, entity.target)
        val candidates = ArrayList<Pair<Vec3, Double>>()
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0
        for (offset in OFFSETS) {
            if (!TickBudget.hasRaycasts(level)) {
                if (candidates.isEmpty()) return Result(Outcome.DEFERRED)
                break
            }
            val raw = task.anchor.add(offset)
            if (!level.chunkSource.hasChunk(BlockPos.containing(raw).x shr 4, BlockPos.containing(raw).z shr 4)) continue
            val candidate = Terrain.standableOrNull(level, raw.x, raw.y, raw.z, 12) ?: continue
            if (!TacticalRules.withinOrder(view, candidate, homeRadius)) continue
            if (kotlin.math.abs(candidate.y - entity.y) > 12.0 || candidate.distanceTo(entity.position()) > 48.0) continue
            if (!level.getFluidState(BlockPos.containing(candidate)).isEmpty) continue
            val body = entity.getDimensions(entity.pose).makeBoundingBox(candidate)
            if (!level.noCollision(entity, body) || hulls.any { it.intersects(body) } ||
                GrenadeHazard.threatens(level, candidate) || FiringSpots.crowded(candidate, taken)) continue
            val eye = candidate.add(0.0, entity.eyeHeight.toDouble(), 0.0)
            var exposure = 0
            var firing = task.focus == null
            for (threat in threats) {
                if (!Sightline.blockedBy(level, threat.position.add(0.0, 1.5, 0.0), candidate.add(0.0, 1.0, 0.0), entity, hulls)) exposure++
            }
            if (task.focus != null) firing = !Sightline.blockedBy(level, eye, task.focus.add(0.0, 1.5, 0.0), entity, hulls)
            val mustShoot = task.job == TacticalJob.COVER || task.job == TacticalJob.ANTI_ARMOUR
            if (mustShoot && !firing) continue
            var score = candidate.distanceTo(task.anchor) + candidate.distanceTo(entity.position()) * 0.2 + exposure * 5.0
            if (firing && mustShoot) score -= 8.0
            candidates += candidate to score
        }
        for ((candidate, _) in candidates.sortedBy { it.second }.take(3)) {
            val path = entity.navigation.createPath(candidate.x, candidate.y, candidate.z, 0) ?: continue
            if (path.canReach()) {
                entity.navigation.moveTo(path, if (task.job in RUNNING_JOBS) 1.3 else 1.0)
                FiringSpots.claim(entity.uuid, candidate)
                return Result(Outcome.FOUND, candidate)
            }
        }
        return Result(Outcome.UNREACHABLE)
    }

    private val RUNNING_JOBS = setOf(TacticalJob.ADVANCE, TacticalJob.FLANK, TacticalJob.REGROUP, TacticalJob.RETREAT)
    private val OFFSETS = listOf(Vec3.ZERO, Vec3(4.0,0.0,0.0), Vec3(-4.0,0.0,0.0), Vec3(0.0,0.0,4.0), Vec3(0.0,0.0,-4.0),
        Vec3(4.0,0.0,4.0), Vec3(-4.0,0.0,4.0), Vec3(4.0,0.0,-4.0), Vec3(-4.0,0.0,-4.0))
}
