package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.IncomingFire
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One cover episode's bounded peek search; rejected candidates survive admission delays. */
internal class CoverPeekSearch(private val cover: BlockPos, private val aim: Vec3, private val targetId: UUID?, private val blind: Boolean) {
    data class Result(val point: Vec3, val path: Path)
    private val base = Vec3.atBottomCenterOf(cover)
    private val forward = aim.subtract(base).multiply(1.0, 0.0, 1.0).normalize()
    private val side = Vec3(-forward.z, 0.0, forward.x)
    private val probes = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0).flatMap { distance ->
        listOf(side, side.scale(-1.0), forward).map { base.add(it.scale(distance)) }
    }
    private var cursor = 0
    val exhausted get() = cursor >= probes.size

    fun matches(cover: BlockPos, aim: Vec3, target: LivingEntity?, blind: Boolean) = this.cover == cover && this.targetId == target?.uuid && this.blind == blind && this.aim.distanceToSqr(aim) <= 16.0

    /** Null is either pending admission or exhausted; callers must distinguish [exhausted]. */
    fun find(entity: NpcEntity, level: ServerLevel, aim: Vec3, target: LivingEntity?): Result? {
        if ((entity.navigation as? VehicleAwareNavigation)?.canPlan == false || exhausted) return null
        return TickBudget.search(entity, TickBudget.Work.COVER, null as Result?) {
            val hulls = Sightline.vehicleHulls(level, AABB(base, aim).inflate(3.0), entity, target)
            val spread = if (blind) maxOf(entity.spread, 9.0) else entity.spread
            while (!exhausted && TickBudget.hasRaycasts(level)) {
                val candidate = Terrain.feetAt(level, probes[cursor]) { feet ->
                    level.noCollision(entity, entity.getDimensions(entity.pose).makeBoundingBox(feet))
                }
                if (candidate == null || candidate.distanceTo(base) > 3.5) {
                    cursor++
                    continue
                }
                val eye = candidate.add(0.0, entity.eyeHeight.toDouble(), 0.0)
                val lane = if (blind) IncomingFire.laneEnd(eye, aim) else aim
                if (Sightline.blockedBy(level, eye, lane, entity, hulls, spread) ||
                    !FriendlyFireGuard.assess(entity, aim, spread, aim, 0.0, eye).lineClear
                ) {
                    cursor++
                    continue
                }
                if (!TacticalBudget.path(entity)) return@search null
                cursor++
                val path = TacticalPositions.pathToPosition(entity, candidate)
                if (TacticalPositions.reaches(path, candidate)) return@search Result(candidate, path!!)
                // One native attempt per actor/tick; resume at the next probe next time.
                return@search null
            }
            null
        }
    }
}
