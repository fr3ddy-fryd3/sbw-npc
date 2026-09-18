package com.sbwnpc.squad.entity.ai

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

/**
 * Pure control math for flying an SBW `DroneEntity` from the server — no Minecraft level access,
 * so it's unit-testable and the behaviour that owns the drone stays thin.
 *
 * How SBW's drone actually responds (read from `DroneEntity.travel`, not assumed):
 *  - `forwardInputDown`/`backInputDown` tilt the body (`DELTA_X_ROT`), and that tilt is what
 *    produces thrust along the drone's forward vector — so holding forward accelerates hard
 *    (terminal speed is well over 2 blocks/tick) and holding back brakes;
 *  - `upInputDown`/`downInputDown` set vertical velocity directly (0.05/tick per held tick, up to
 *    5 ticks); with neither held the drone roughly hovers;
 *  - `leftInputDown`/`rightInputDown` are a sideways strafe, NOT a turn. Yaw only ever changes from
 *    the player's mouse, which we don't have — so the controller emits an absolute yaw for the
 *    caller to write into `yRot`, stepped by at most [MAX_YAW_STEP] per tick so the turn reads as a
 *    turn and not a snap.
 *
 * The caller decides the phase (cruise vs attack) and the altitude to hold; this only turns
 * "be there, that fast" into inputs. Vanilla yaw convention: 0 = +Z, 90 = -X, forward =
 * (-sin yaw, 0, cos yaw) — matches `VehicleEntity.getForwardDirection`.
 */
object DroneFlightController {
    /** One tick's worth of inputs plus the yaw to apply this tick. */
    class Command(
        val forward: Boolean,
        val back: Boolean,
        val up: Boolean,
        val down: Boolean,
        val yaw: Float
    )

    const val MAX_YAW_STEP = 6f
    /** Don't add thrust unless roughly pointed at the target — otherwise a fast drone that's still
     *  turning sails off on a wide arc and has to come back around. */
    private const val THRUST_HEADING_TOLERANCE = 30f
    private const val ALTITUDE_DEADBAND = 1.0

    /** Degrees, vanilla convention, from [from] toward [to] (horizontal only). */
    fun yawToward(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return Math.toDegrees(atan2(-dx, dz)).toFloat()
    }

    /** [currentYaw] moved toward [desiredYaw] by at most [MAX_YAW_STEP], along the short way round. */
    fun stepYaw(currentYaw: Float, desiredYaw: Float): Float {
        val error = Mth.wrapDegrees(desiredYaw - currentYaw)
        return currentYaw + error.coerceIn(-MAX_YAW_STEP, MAX_YAW_STEP)
    }

    /**
     * @param pos            drone position
     * @param yaw            drone's current yRot
     * @param horizontalSpeed drone's current horizontal speed, blocks/tick
     * @param target         point to fly toward (its Y is ignored — [desiredY] owns altitude)
     * @param desiredY       absolute Y to hold
     * @param cruiseSpeed    horizontal speed to settle at; thrust is only added below it and
     *                       braking applied well above it, so the drone stays controllable
     */
    fun steer(pos: Vec3, yaw: Float, horizontalSpeed: Double, target: Vec3, desiredY: Double, cruiseSpeed: Double): Command {
        val desiredYaw = yawToward(pos, target)
        val newYaw = stepYaw(yaw, desiredYaw)
        val headingError = Mth.abs(Mth.wrapDegrees(desiredYaw - newYaw))
        val pointedAtTarget = headingError <= THRUST_HEADING_TOLERANCE

        val forward = pointedAtTarget && horizontalSpeed < cruiseSpeed
        val back = horizontalSpeed > cruiseSpeed * 1.4 || (!pointedAtTarget && horizontalSpeed > cruiseSpeed * 0.5)

        val dy = desiredY - pos.y
        val up = dy > ALTITUDE_DEADBAND
        val down = dy < -ALTITUDE_DEADBAND
        return Command(forward, back, up, down, newYaw)
    }

    /** Cruise altitude for a flight leg: the highest terrain sampled along the leg plus the clearance,
     *  never below the drone's current height minus a gentle descent so it doesn't dive into a
     *  valley only to climb straight back out. */
    fun cruiseAltitude(terrainHeights: List<Int>, clearance: Double, currentY: Double): Double {
        val highest = terrainHeights.maxOrNull() ?: return currentY
        val wanted = highest + clearance
        return maxOf(wanted, currentY - 0.5)
    }
}
