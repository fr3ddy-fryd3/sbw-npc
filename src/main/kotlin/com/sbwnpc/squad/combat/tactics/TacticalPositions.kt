package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Local positions are validated through the existing navigation and collider raycasts. */
object TacticalPositions {
    enum class Outcome { FOUND, DEFERRED, UNREACHABLE }
    data class Result(val outcome: Outcome, val position: Vec3? = null)

    fun find(entity: NpcEntity, task: TacticalTask, view: TacticalSnapshot): Result {
        val level = entity.level() as ServerLevel
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false) return Result(Outcome.DEFERRED)
        val focus=task.focus
        val overwatch=task.job==TacticalJob.OVERWATCH
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0 + if (overwatch) DefensiveOverwatch.RADIUS else 0.0
        val member = view.members.firstOrNull { it.id == entity.uuid }
        if (task.job == TacticalJob.COVER && member != null && TacticalManeuvers.covers(member,task) &&
            member.position.distanceTo(task.anchor) <= 10.0 &&
            TacticalRules.withinOrder(view,member.position,homeRadius) && !GrenadeHazard.threatens(level,member.position))
            return finish(task,member.position)
        if (!TickBudget.hasRaycasts(level)) return Result(Outcome.DEFERRED)
        val taken = FiringSpots.nearbyWithBodies(level,entity,64.0)
        val threats = view.visible.sortedByDescending { it.priority }.take(3)
        val hulls = Sightline.vehicleHulls(level,entity.boundingBox.inflate(64.0),entity,entity.target)
        val localAnchor = task.staging ?: if (task.job in RUNNING_JOBS + TacticalJob.SEARCH) TacticalRoutes.leg(entity.position(),task) else task.anchor
        if (task.search?.origin?.distanceTo(entity.position())?.let { it > 4.0 } == true) task.search=null
        if (overwatch && task.search==null) entity.navigation.stop()
        val search = task.search ?: TacticalPositionSearch(entity.position(),
            if (overwatch) DefensiveOverwatch.probes(task.anchor) else OFFSETS.map { localAnchor.add(it) }).also { task.search=it }
        val plan = entity.currentSquad()?.tactics?.plan
        val pattern = plan?.pattern
        val covered = plan!=null && view.members.count {
            plan.tasks[it.id]?.job==TacticalJob.COVER && TacticalManeuvers.firesCover(it,plan.tasks[it.id])
        } >= TacticalFlanks.requiredCover(view)
        var probesThisTick=0
        while (search.probeIndex < search.probes.size) {
            if (!TickBudget.hasRaycasts(level) || probesThisTick++ >= 24) return Result(Outcome.DEFERRED)
            val raw=search.probes[search.probeIndex++]
            val block=BlockPos.containing(raw)
            if (!level.chunkSource.hasChunk(block.x shr 4,block.z shr 4)) continue
            val candidate=Terrain.feetAt(level,raw,if (overwatch) 9 else 12) { level.noCollision(entity,entity.getDimensions(entity.pose).makeBoundingBox(it)) } ?: continue
            if (!search.visited.add(candidate) || !TacticalRules.withinOrder(view,candidate,homeRadius)) continue
            if (overwatch && !DefensiveOverwatch.within(task.anchor,candidate)) continue
            if (pattern == TacticalPattern.HOLD_HEIGHT && candidate.y < view.center.y-2.0) continue
            if (pattern == TacticalPattern.FILE && task.job == TacticalJob.ADVANCE && candidate.distanceTo(localAnchor)>1.0) continue
            if (!overwatch && (kotlin.math.abs(candidate.y-entity.y)>12.0 || candidate.distanceTo(entity.position())>48.0)) continue
            val body=entity.getDimensions(entity.pose).makeBoundingBox(candidate)
            if (!safeBody(entity,candidate,body,hulls,taken)) continue
            val eye=candidate.add(0.0,entity.eyeHeight.toDouble(),0.0)
            if (overwatch && !protected(entity,task,candidate,hulls)) continue
            val exposure=threats.count { !Sightline.blockedBy(level,it.position.add(0.0,1.5,0.0),candidate.add(0.0,1.0,0.0),entity,hulls) }
            val mustShoot=task.job in setOf(TacticalJob.COVER,TacticalJob.ANTI_ARMOUR,TacticalJob.OVERWATCH)
            val firing=focus?.let { !Sightline.blockedBy(level,eye,it.add(0.0,1.5,0.0),entity,hulls) } ?: true
            if (mustShoot && (!firing || !friendlyLane(entity,eye,focus))) continue
            var score=candidate.distanceTo(localAnchor)+candidate.distanceTo(entity.position())*0.2+exposure*5.0
            if (firing && mustShoot) score-=8.0
            if (pattern in setOf(TacticalPattern.AVOID_ARMOUR,TacticalPattern.BREAK_CONTACT,TacticalPattern.REORGANIZE)) score+=exposure*10.0
            if (pattern == TacticalPattern.ATTACK_HEIGHT && task.job in RUNNING_JOBS && focus != null) score+=maxOf(0.0,focus.y-candidate.y)*1.5
            if (pattern == TacticalPattern.HOLD_HEIGHT) score+=maxOf(0.0,view.center.y-candidate.y)*8.0
            if (overwatch) score=DefensiveOverwatch.score(task.anchor,search.origin,candidate,exposure)
            search.scored[candidate]=score
        }
        val destinations=search.destinations ?: search.scored.entries.sortedBy { it.value }.map { it.key }.also { search.destinations=it }
        while (search.destinationIndex < destinations.size) {
            if (!TickBudget.hasRaycasts(level) || !TacticalBudget.path(level.gameTime)) return Result(Outcome.DEFERRED)
            val candidate=destinations[search.destinationIndex]
            val body=entity.getDimensions(entity.pose).makeBoundingBox(candidate)
            val path=if (safeBody(entity,candidate,body,hulls,taken) && (!overwatch || protected(entity,task,candidate,hulls)))
                pathToPosition(entity,candidate) else null
            if (reaches(path,candidate)) {
                var safe=true
                for (index in 0 until path!!.nodeCount) {
                    val node=path.getNode(index)
                    val foot=Vec3(node.x+0.5,node.y.toDouble(),node.z+0.5)
                    if (!TacticalRules.withinOrder(view,foot,homeRadius) || GrenadeHazard.threatens(level,foot) ||
                        (pattern == TacticalPattern.HOLD_HEIGHT && foot.y < view.center.y-2.0)) { safe=false; break }
                    if (index % 4 == 0 && task.job == TacticalJob.FLANK) {
                        if (!TickBudget.hasRaycasts(level)) return Result(Outcome.DEFERRED)
                        if (threats.any { it.position.distanceTo(foot)<8.0 }) { safe=false; break }
                        val threat=threats.firstOrNull()
                        if (!covered && threat != null && foot.distanceTo(threat.position)<entity.position().distanceTo(threat.position)*0.6 &&
                            !Sightline.blocked(level,threat.position.add(0.0,1.5,0.0),foot.add(0.0,1.0,0.0),entity) &&
                            pattern != TacticalPattern.ENCIRCLE) { safe=false; break }
                    }
                }
                if (safe && entity.navigation.moveTo(path,speed(task))) {
                    FiringSpots.claim(entity.uuid,candidate)
                    return finish(task,candidate)
                }
            }
            search.destinationIndex++
        }
        task.search=null
        return Result(Outcome.UNREACHABLE)
    }

    internal fun reaches(path: Path?, candidate: Vec3): Boolean = path?.canReach()==true && path.endNode?.let {
        Vec3(it.x+0.5,it.y.toDouble(),it.z+0.5).distanceTo(candidate)<=2.5
    } == true

    fun pathToPosition(entity: NpcEntity,point: Vec3): Path? {
        val navigation=entity.navigation
        return if (navigation is VehicleAwareNavigation) navigation.createPositionPath(BlockPos.containing(point))
            else navigation.createPath(point.x,point.y,point.z,0)
    }

    fun speed(task: TacticalTask): Double = if (task.job in RUNNING_JOBS + TacticalJob.OVERWATCH) 1.3 else 1.0

    fun protected(entity: NpcEntity,task: TacticalTask,point: Vec3): Boolean {
        val level=entity.level() as ServerLevel
        return protected(entity,task,point,Sightline.vehicleHulls(level,entity.boundingBox.inflate(64.0),entity,entity.target))
    }

    private fun protected(entity: NpcEntity,task: TacticalTask,point: Vec3,hulls: List<AABB>): Boolean =
        DefensiveOverwatch.protected(point,entity.eyeHeight.toDouble(),task.focus) { from,to ->
            Sightline.blockedBy(entity.level() as ServerLevel,from,to,entity,hulls)
        } && friendlyLane(entity,point.add(0.0,entity.eyeHeight.toDouble(),0.0),task.focus)

    private fun finish(task: TacticalTask,point: Vec3): Result { task.search=null; return Result(Outcome.FOUND,point) }
    private fun safeBody(entity: NpcEntity,point: Vec3,body: AABB,hulls: List<AABB>,taken: List<Vec3>): Boolean =
        entity.level().getFluidState(BlockPos.containing(point)).isEmpty && entity.level().noCollision(entity,body) &&
            hulls.none { it.intersects(body) } && !GrenadeHazard.threatens(entity.level() as ServerLevel,point) && !FiringSpots.crowded(point,taken)
    private fun friendlyLane(entity: NpcEntity,eye: Vec3,focus: Vec3?): Boolean = focus == null ||
        FriendlyFireGuard.assess(entity,focus.add(0.0,1.5,0.0),3.0,focus,0.0,eye).lineClear
    private val RUNNING_JOBS=setOf(TacticalJob.ADVANCE,TacticalJob.FLANK,TacticalJob.REGROUP,TacticalJob.RETREAT)
    private val OFFSETS=listOf(Vec3.ZERO,Vec3(0.8,0.0,0.0),Vec3(-0.8,0.0,0.0),Vec3(0.0,0.0,0.8),Vec3(0.0,0.0,-0.8),
        Vec3(4.0,0.0,0.0),Vec3(-4.0,0.0,0.0),Vec3(0.0,0.0,4.0),Vec3(0.0,0.0,-4.0),
        Vec3(4.0,0.0,4.0),Vec3(-4.0,0.0,4.0),Vec3(4.0,0.0,-4.0),Vec3(-4.0,0.0,-4.0))
}
