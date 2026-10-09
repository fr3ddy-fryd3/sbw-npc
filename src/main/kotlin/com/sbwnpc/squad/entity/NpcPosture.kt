package com.sbwnpc.squad.entity

import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.Pose

/**
 * Down on one knee once settled at a position: in cover, dug in, holding a defend post, or
 * fighting from where it stands. Up again the moment it moves off. The eyes stay high enough to see
 * and shoot over a one-block wall; where crouching still hides the target — a taller lip — the NPC
 * stands to shoot and stays up a while, rather than bobbing up and down every tick.
 */
class NpcPosture(private val npc: NpcEntity) {
    /** Ticks it has stood still in a position worth crouching in. */
    private var stillTicks = 0

    /** Crouching cost it its line of fire: stays up until this tick. */
    private var standUntilTick = 0

    fun tick() {
        if (npc.pose != Pose.STANDING && npc.pose != Pose.CROUCHING) return
        val tick = npc.tickCount
        val moving = !npc.navigation.isDone || npc.deltaMovement.horizontalDistanceSqr() > STILL_SPEED_SQR
        val position = npc.combatLockedByCover() || npc.diggedIn || npc.target != null ||
            npc.currentSquad()?.order == SquadOrder.DEFEND
        stillTicks = if (!moving && npc.onGround() && !npc.isPassenger && !npc.isInWater && position) stillTicks + 1 else 0
        val blockedSince = npc.blockedSightSince
        if (npc.isCrouching && npc.target != null && blockedSince != null && tick - blockedSince >= SIGHT_LOST_STAND_TICKS) {
            standUntilTick = tick + STAND_TO_SHOOT_TICKS
        }
        val squad = npc.currentSquad()
        val task = squad?.tactics?.plan?.takeIf { it.stamp == squad.orderStamp }?.tasks?.get(npc.uuid)
        // A quiet sentry must be able to discover enemies below its parapet. Waiting for a
        // blocked target to stand up cannot work when crouching prevents first discovery.
        val overwatch = task?.job == TacticalJob.OVERWATCH && !task.localFireRecovery
        val scanningOverwatch = overwatch && npc.target == null
        var crouch = stillTicks >= CROUCH_AFTER_TICKS && tick >= standUntilTick && !scanningOverwatch
        if (crouch && overwatch && !npc.isCrouching && npc.target != null) {
            val level = npc.level() as? ServerLevel
            if (level != null) {
                if (!TickBudget.hasRaycasts(level)) {
                    crouch = false
                } else if (Sightline.blocked(level, npc.position().add(0.0, CROUCHING_DIMENSIONS.eyeHeight().toDouble(), 0.0), npc.target!!.eyePosition, npc)) {
                    crouch = false
                    standUntilTick = tick + STAND_TO_SHOOT_TICKS
                }
            }
        }
        if (crouch && !npc.isCrouching) {
            npc.pose = Pose.CROUCHING
        } else if (!crouch && npc.isCrouching && canStandUp()) {
            npc.pose = Pose.STANDING
        }
    }

    private fun canStandUp(): Boolean = npc.level().noCollision(npc, npc.getDimensions(Pose.STANDING).makeBoundingBox(npc.position()).deflate(1.0e-7))

    companion object {
        /** A player's crouch, scaled to the NPC's height; the eyes clear a one-block wall. */
        val CROUCHING_DIMENSIONS: EntityDimensions = EntityDimensions.scalable(0.6f, 1.55f).withEyeHeight(1.35f)
        private const val STILL_SPEED_SQR = 0.0025
        private const val CROUCH_AFTER_TICKS = 10
        private const val SIGHT_LOST_STAND_TICKS = 10
        private const val STAND_TO_SHOOT_TICKS = 100
    }
}
