package com.sbwnpc.squad.integration.sbw

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Conservative envelope around SBW ProjectileEntity.getHitResult's moving AABB. */
internal object SbwProjectileSafety {
    private const val REWIND_TICKS = 5.0
    private const val PLAYER_OFFSET_TICKS = 3.0
    private const val RIDER_OFFSET_TICKS = 2.5
    private const val PLAYER_RIDER_OFFSET_TICKS = 1.5
    private const val STANDING_PLAYER_EXTRA_HEIGHT = 0.0625
    private const val MOVEMENT_MARGIN = 0.1

    fun bounds(entity: LivingEntity, flightTicks: Double): AABB {
        val motion = Vec3(entity.x - entity.xOld, entity.y - entity.yOld, entity.z - entity.zOld)
        // NPC posture can change after authorization but before the round's collision tick.
        // A crouched head is not a durable firing lane above a squadmate.
        val postureBody = if (entity is NpcEntity) entity.boundingBox.minmax(entity.getDimensions(Pose.STANDING).makeBoundingBox(entity.position())) else entity.boundingBox
        val body = if (entity is Player && !entity.isCrouching) postureBody.expandTowards(0.0, STANDING_PLAYER_EXTRA_HEIGHT, 0.0) else postureBody
        var offset = -REWIND_TICKS
        if (entity is ServerPlayer) {
            offset += PLAYER_OFFSET_TICKS
            if (entity.isPassenger) offset += PLAYER_RIDER_OFFSET_TICKS
        }
        if (entity.isPassenger) offset -= RIDER_OFFSET_TICKS
        return envelope(body, motion, entity.deltaMovement, offset, flightTicks)
    }

    internal fun envelope(body: AABB, motion: Vec3, velocity: Vec3, offset: Double, flightTicks: Double): AABB {
        val collision = body.expandTowards(motion).move(motion.scale(offset))
        return body.minmax(collision)
            .expandTowards(motion.scale(flightTicks))
            .expandTowards(velocity.scale(flightTicks))
            .inflate(MOVEMENT_MARGIN)
    }
}
