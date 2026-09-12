package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.RouteManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * Squad-order movement. Low priority — the gun goal (chase & shoot) always wins when there's an
 * enemy; this only steers the NPC when it's otherwise idle.
 *
 *  - DEFEND: return to within ~8 blocks of home (objective point / guarded entity), then hold.
 *  - PATROL: walk the squad's assigned Route in sequence if it has one (see RouteManager);
 *    otherwise wander within ~12 blocks of home as before.
 *  - ATTACK: advance to home.
 *  - FREE / no squad / no home: inactive.
 *
 * Destination points go through [SquadFormation.slotTarget] instead of the bare anchor — every
 * member walking toward the literal same coordinate was what actually caused the pileup/"snake"
 * (they'd shove past each other via vanilla collision avoidance the whole way in). Once a member is
 * actually at its objective ([approachSlot]'s `arrived`), the formation switches to a stationary
 * perimeter (ring, facing outward) instead of staying frozen in a marching wedge/line.
 */
class SquadOrderGoal(private val mob: NpcEntity) : Goal() {

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    private var repathCooldown = 0
    private var routeIndex = 0
    private var routeWaitUntil = 0

    override fun canUse(): Boolean {
        if (mob.target != null) return false
        val squad = mob.currentSquad() ?: return false
        return squad.order != SquadOrder.FREE && mob.homeCenter() != null
    }

    override fun canContinueToUse() = canUse()

    override fun tick() {
        val squad = mob.currentSquad() ?: return
        val order = squad.order
        val home = mob.homeCenter() ?: return
        val dist = mob.position().distanceTo(home)
        if (repathCooldown > 0) repathCooldown--

        when (order) {
            SquadOrder.ATTACK -> approachSlot(home, arrived = dist <= SquadFormation.ARRIVAL_RADIUS)
            SquadOrder.DEFEND -> approachSlot(home, arrived = dist <= 8.0)
            SquadOrder.PATROL -> {
                val points = squad.routeId
                    ?.let { (mob.level() as? ServerLevel)?.let { lvl -> RouteManager.get(lvl).get(it) } }
                    ?.points
                if (!points.isNullOrEmpty()) {
                    tickRoute(points)
                } else if (mob.navigation.isDone && repathCooldown == 0) {
                    wanderNear(home)
                    repathCooldown = 40 + mob.random.nextInt(40)
                }
            }
            SquadOrder.FREE -> {}
        }
    }

    /** Walks toward [anchor]'s formation slot, switching to a held perimeter slot once [arrived].
     *  Shared by ATTACK/DEFEND — the only difference between them is the distance that counts as
     *  "arrived" (passed in by the caller). */
    private fun approachSlot(anchor: Vec3, arrived: Boolean) {
        val slot = SquadFormation.slotTarget(mob, anchor, anchor.subtract(mob.position()), arrived)
        if (mob.position().distanceTo(slot) > 1.5) {
            if (repathCooldown == 0) {
                mob.navigation.moveTo(slot.x, slot.y, slot.z, 1.0)
                repathCooldown = 20
            }
        } else {
            mob.navigation.stop()
        }
        if (arrived && mob.target == null) faceOutward(anchor)
    }

    /** While holding a perimeter slot with nothing to shoot at, look away from the anchor instead
     *  of standing there facing an arbitrary direction — "guns pointing outward" per user feedback.
     *  No-op once there's a real target: NpcGunAttackGoal's own lookAt (higher priority) takes over. */
    private fun faceOutward(anchor: Vec3) {
        val out = mob.position().subtract(anchor)
        if (out.lengthSqr() < 1.0e-6) return
        val dir = out.normalize()
        val lookAt = mob.position().add(dir.scale(10.0))
        mob.lookControl.setLookAt(lookAt.x, mob.eyePosition.y, lookAt.z)
    }

    /** Walks [points] in order, waiting a couple of seconds at each before moving to the next —
     *  falls back to the old random-wander behavior (see [tick]) when the squad has no route. The
     *  dwell at each point uses the RING/outward-facing perimeter too, same as ATTACK/DEFEND. */
    private fun tickRoute(points: List<BlockPos>) {
        val target = points[routeIndex.coerceIn(points.indices)]
        val center = target.center
        val dwelling = mob.tickCount < routeWaitUntil
        val slot = SquadFormation.slotTarget(mob, center, center.subtract(mob.position()), dwelling)

        if (!dwelling && mob.position().distanceTo(slot) <= 2.5) {
            routeIndex = (routeIndex + 1) % points.size
            routeWaitUntil = mob.tickCount + ROUTE_DWELL_TICKS + mob.random.nextInt(ROUTE_DWELL_JITTER)
            return
        }
        if (dwelling && mob.target == null) faceOutward(center)
        if (mob.position().distanceTo(slot) > 1.5 && repathCooldown == 0) {
            mob.navigation.moveTo(slot.x, slot.y, slot.z, 1.0)
            repathCooldown = 20
        }
    }

    private fun wanderNear(center: Vec3) {
        val target = DefaultRandomPos.getPosTowards(mob, 12, 6, center, Math.PI / 2.0) ?: return
        mob.navigation.moveTo(target.x, target.y, target.z, 0.9)
    }

    companion object {
        private const val ROUTE_DWELL_TICKS = 40
        private const val ROUTE_DWELL_JITTER = 40
    }
}
