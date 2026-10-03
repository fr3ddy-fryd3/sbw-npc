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
        val localAnchor = if (task.job in RUNNING_JOBS + TacticalJob.SEARCH) TacticalRoutes.leg(entity.position(),task) else task.anchor
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0
        for (offset in OFFSETS) {
            if (!TickBudget.hasRaycasts(level)) {
                if (candidates.isEmpty()) return Result(Outcome.DEFERRED)
                break
            }
            val raw = localAnchor.add(offset)
            if (!level.chunkSource.hasChunk(BlockPos.containing(raw).x shr 4, BlockPos.containing(raw).z shr 4)) continue
            val candidate = Terrain.standableOrNull(level, raw.x, raw.y, raw.z, 12) ?: continue
            if (!TacticalRules.withinOrder(view, candidate, homeRadius)) continue
            val pattern = entity.currentSquad()?.tactics?.plan?.pattern
            if (pattern == TacticalPattern.HOLD_HEIGHT && candidate.y < view.center.y - 2.0) continue
            if (pattern == TacticalPattern.FILE && task.job == TacticalJob.ADVANCE && candidate.distanceTo(localAnchor) > 1.0) continue
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
            var score = candidate.distanceTo(localAnchor) + candidate.distanceTo(entity.position()) * 0.2 + exposure * 5.0
            if (firing && mustShoot) score -= 8.0
            if (pattern in setOf(TacticalPattern.AVOID_ARMOUR,TacticalPattern.BREAK_CONTACT,TacticalPattern.REORGANIZE)) score += exposure*10.0
            if (pattern == TacticalPattern.ATTACK_HEIGHT && task.focus != null) score += maxOf(0.0,task.focus.y-candidate.y)*1.5
            if (pattern == TacticalPattern.HOLD_HEIGHT) score += maxOf(0.0,view.center.y-candidate.y)*8.0
            candidates += candidate to score
        }
        for ((candidate, _) in candidates.sortedBy { it.second }.take(3)) {
            if (!TacticalBudget.path(level.gameTime)) return Result(Outcome.DEFERRED)
            val path = entity.navigation.createPath(candidate.x, candidate.y, candidate.z, 0) ?: continue
            if (path.canReach()) {
                var safe = true
                val pattern = entity.currentSquad()?.tactics?.plan?.pattern
                for (index in 0 until path.nodeCount) {
                    val node = path.getNode(index)
                    val foot = Vec3(node.x+0.5,node.y.toDouble(),node.z+0.5)
                    if (!TacticalRules.withinOrder(view,foot,homeRadius) || GrenadeHazard.threatens(level,foot) ||
                        (pattern == TacticalPattern.HOLD_HEIGHT && foot.y < view.center.y-2.0)) { safe=false; break }
                    if (index % 4 == 0 && task.job == TacticalJob.FLANK) {
                        if (!TickBudget.hasRaycasts(level)) return Result(Outcome.DEFERRED)
                        if (threats.any { it.position.distanceTo(foot) < 8.0 }) { safe=false; break }
                        val threat = threats.firstOrNull()
                        if (threat != null && foot.distanceTo(threat.position) < entity.position().distanceTo(threat.position)*0.6 &&
                            !Sightline.blocked(level,threat.position.add(0.0,1.5,0.0),foot.add(0.0,1.0,0.0),entity) &&
                            pattern != TacticalPattern.ENCIRCLE) { safe=false; break }
                    }
                }
                if (!safe) continue
                entity.navigation.moveTo(path, if (task.job in RUNNING_JOBS) 1.3 else 1.0)
                FiringSpots.claim(entity.uuid, candidate)
                return Result(Outcome.FOUND, candidate)
            }
        }
        return Result(Outcome.UNREACHABLE)
    }

    private val RUNNING_JOBS = setOf(TacticalJob.ADVANCE, TacticalJob.FLANK, TacticalJob.REGROUP, TacticalJob.RETREAT)
    private val OFFSETS = listOf(Vec3.ZERO, Vec3(0.8,0.0,0.0), Vec3(-0.8,0.0,0.0), Vec3(0.0,0.0,0.8), Vec3(0.0,0.0,-0.8), Vec3(4.0,0.0,0.0), Vec3(-4.0,0.0,0.0), Vec3(0.0,0.0,4.0), Vec3(0.0,0.0,-4.0),
        Vec3(4.0,0.0,4.0), Vec3(-4.0,0.0,4.0), Vec3(4.0,0.0,-4.0), Vec3(-4.0,0.0,-4.0))
}
