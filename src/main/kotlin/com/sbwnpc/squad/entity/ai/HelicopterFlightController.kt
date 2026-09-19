package com.sbwnpc.squad.entity.ai

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * Pure control math for flying an SBW helicopter from the server — no Minecraft level access, so
 * it's unit-testable and the behaviour that owns the aircraft stays thin.
 *
 * How SBW's helicopter actually responds (read from `VehicleEngineUtils.helicopterEngine`, not
 * assumed from the drone — almost every axis differs):
 *  - `forwardInputDown` is the COLLECTIVE UP, not thrust: it raises `power` (capped at 0.12), and
 *    the first press also starts the engine. `downInputDown` bleeds power to a floor of
 *    `0.035 / liftSpeed` (fast sink), `backInputDown` to `0.058 / liftSpeed` (gentle sink).
 *  - `upInputDown` is NOT climb — it TOGGLES [Command.hoverMode]. Writing it every tick would flip
 *    hover on and off twenty times a second, so nothing here ever emits it; the caller sets the
 *    vehicle's `hoverMode` property directly instead.
 *  - `leftInputDown` and `rightInputDown` are yaw pedals (they feed `deltaRot`, which also banks
 *    the airframe), not a strafe.
 *  - Attitude comes from `mouseMoveSpeedX` and `mouseMoveSpeedY` — the cyclic stick. There is no
 *    way to set yaw directly: the engine does `yRot += yawSpeed * clamp(2 * mouseMoveSpeedX *
 *    propellerRot, -10, 10)` every tick, so writing `yRot` would fight it. We command the stick.
 *  - Lift is always along the airframe's own up vector
 *    (`deltaMovement += upVec * propellerRot * liftSpeed * 0.66`), so the ONLY way to move
 *    horizontally is to tilt: nose down (positive `xRot`) accelerates forward, nose up brakes.
 *  - Every control authority is multiplied by `propellerRot`, which lerps toward `power` at
 *    0.18/tick — the rotor spools up and control response varies with it. [Tuning.authority]
 *    carries it so the commands below invert the physics instead of guessing a fixed gain.
 *  - With hover mode on the engine pre-scales `pitchSpeed` by 0.2 and `yawSpeed` by 0.5, auto-levels
 *    pitch and roll, damps horizontal movement by 0.95/tick and actively holds altitude against
 *    vertical speed. That makes it the right mode for station keeping and the wrong one for
 *    getting anywhere, so [steer] turns it off while translating.
 *
 * Reference numbers for the stock airframes (gravity is 0.06/tick, `liftSpeed` 1.0 on both AH-6
 * and Mi-28): hover sits at `propellerRot` around 0.091, and the 0.12 cap gives roughly 0.38
 * blocks/tick of climb once vertical drag settles.
 *
 * Vanilla yaw convention: 0 = +Z, 90 = -X, forward = (-sin yaw, 0, cos yaw).
 */
object HelicopterFlightController {

    /** What the collective should do this tick; the caller maps these onto the input flags. */
    enum class Collective { CLIMB, HOLD, SINK_SLOW, SINK_FAST }

    /** One tick's worth of commands. Nothing here maps to `upInputDown` — see the class doc. */
    class Command(
        val collective: Collective,
        val mouseX: Float,
        val mouseY: Float,
        val hoverMode: Boolean,
        val rollLeft: Boolean = false,
        val rollRight: Boolean = false
    )

    /**
     * The airframe's current control authority and its engine tuning, so commands can be inverted
     * out of the real physics rather than fitted to one helicopter.
     *
     * @param authority  the vehicle's current `propellerRot`
     * @param yawSpeed   `EngineInfo.Helicopter.yawSpeed`
     * @param pitchSpeed `EngineInfo.Helicopter.pitchSpeed`
     */
    class Tuning(val authority: Float, val yawSpeed: Float, val pitchSpeed: Float)

    const val MAX_YAW_RATE = 2.5f
    const val MAX_PITCH_RATE = 1.5f
    const val MAX_PITCH_ANGLE = 22f
    /** Below this the rotor is too slow to steer with; commanding a huge stick deflection to
     *  compensate would only snap the airframe around the moment it does spin up. */
    const val MIN_AUTHORITY = 0.02f
    private const val ARRIVE_RADIUS = 6.0
    private const val THRUST_HEADING_TOLERANCE = 35f
    private const val SPEED_TO_PITCH = 45f
    private const val ALTITUDE_DEADBAND = 1.5
    private const val FAST_SINK_MARGIN = 8.0
    private const val ROLL_DEADBAND = 1.5f
    /** `deltaRot` decays by 0.9 a tick, so the roll still owed by the current rate is about 10x it. */
    private const val ROLL_LEAD = 10f

    /** Degrees, vanilla convention, from [from] toward [to] (horizontal only). */
    fun yawToward(from: Vec3, to: Vec3): Float = DroneFlightController.yawToward(from, to)

    fun horizontalDistance(from: Vec3, to: Vec3): Double = Math.hypot(to.x - from.x, to.z - from.z)

    /** Horizontal speed along the line to [target]: positive closing, negative falling behind. */
    fun closingSpeed(pos: Vec3, target: Vec3, velocity: Vec3): Double {
        val dx = target.x - pos.x
        val dz = target.z - pos.z
        val distance = Math.hypot(dx, dz)
        if (distance < 1.0e-6) return 0.0
        return (velocity.x * dx + velocity.z * dz) / distance
    }

    /**
     * Cyclic deflection that produces [yawRate] degrees of turn this tick, inverted out of
     * `yRot += yawSpeed * clamp(2 * mouseMoveSpeedX * propellerRot, -10, 10)`. Returns 0 while the
     * rotor is below [MIN_AUTHORITY] — there is nothing to steer with yet.
     */
    fun yawStick(yawRate: Float, tuning: Tuning, hoverMode: Boolean): Float {
        val yawSpeed = tuning.yawSpeed * if (hoverMode) 0.5f else 1f
        val gain = yawSpeed * 2f * tuning.authority
        if (tuning.authority < MIN_AUTHORITY || gain <= 0f) return 0f
        // The engine clamps its own term to +-10 degrees/tick; asking for more just saturates.
        return (yawRate.coerceIn(-10f, 10f) / gain)
    }

    /** Same inversion for `xRot += 1.5 * pitchSpeed * mouseMoveSpeedY * propellerRot`. */
    fun pitchStick(pitchRate: Float, tuning: Tuning, hoverMode: Boolean): Float {
        val pitchSpeed = tuning.pitchSpeed * if (hoverMode) 0.2f else 1f
        val gain = 1.5f * pitchSpeed * tuning.authority
        if (tuning.authority < MIN_AUTHORITY || gain <= 0f) return 0f
        return pitchRate / gain
    }

    /**
     * Wings-level trim, as a (left, right) pedal pair.
     *
     * The engine gives roll no restoring force at all while a pilot is aboard and hover mode is
     * off — it only ever accumulates, including from the roll term that every yaw input feeds
     * (`setZRot(roll - rollSpeed * (deltaRot + 0.25 * mouseMoveSpeedX * propellerRot))`). So the
     * wings have to be flown level deliberately. The pedals are the clean handle for it: they feed
     * `deltaRot`, which appears only in that roll line, while yaw comes solely from the cyclic.
     * Left reduces roll, right increases it.
     *
     * Steered on where the bank is *heading*, not where it is. The pedals feed `deltaRot`, which is
     * a roll RATE that only decays by 0.9 per tick, so it is an integrator: holding a pedal until
     * the wings read level guarantees sailing straight past level with all that rate still stored,
     * and the correction the other way does the same thing back. [ROLL_LEAD] is that decay's tail
     * (a rate of `r` has about `r / (1 - 0.9)` degrees still to give), so this stops pushing as
     * soon as the roll already in the pipe is enough.
     */
    fun rollTrim(roll: Float, rollRate: Float = 0f): Pair<Boolean, Boolean> {
        val projected = roll + ROLL_LEAD * rollRate
        return when {
            projected > ROLL_DEADBAND -> true to false
            projected < -ROLL_DEADBAND -> false to true
            else -> false to false
        }
    }

    /** Collective for holding [desiredY], with a wider band before the fast sink so an aircraft
     *  that is only slightly high doesn't drop like a stone. */
    fun collectiveFor(currentY: Double, desiredY: Double): Collective {
        val dy = desiredY - currentY
        return when {
            dy > ALTITUDE_DEADBAND -> Collective.CLIMB
            dy < -(ALTITUDE_DEADBAND + FAST_SINK_MARGIN) -> Collective.SINK_FAST
            dy < -ALTITUDE_DEADBAND -> Collective.SINK_SLOW
            else -> Collective.HOLD
        }
    }

    /**
     * Fly toward [target] (its Y is ignored, [desiredY] owns altitude) and hold station once
     * within arrival range.
     *
     * @param pos             aircraft position
     * @param yaw             current `yRot`
     * @param pitch           current `xRot`, positive is nose down
     * @param velocity        current `deltaMovement`. Deliberately the vector and not a speed: the
     *                        nose-up that brakes a fast approach also tilts the lift vector
     *                        backwards, so judging it by unsigned speed means a machine that has
     *                        started sliding backwards still reads as "too fast", keeps the nose
     *                        up, and accelerates away in reverse forever.
     * @param cruiseSpeed     horizontal speed to settle at while translating
     * @param facing          what to point the nose at once on station, when that isn't the point
     *                        being flown to — a hovering gunship faces its target, not the empty
     *                        patch of sky it is holding. Ignored while still translating, since
     *                        there the nose has to point where the lift vector is pushing.
     */
    fun steer(
        pos: Vec3,
        yaw: Float,
        pitch: Float,
        roll: Float,
        rollRate: Float,
        velocity: Vec3,
        target: Vec3,
        desiredY: Double,
        cruiseSpeed: Double,
        tuning: Tuning,
        facing: Vec3? = null
    ): Command {
        val arrived = horizontalDistance(pos, target) <= ARRIVE_RADIUS
        // Hover mode kills pitch authority and damps horizontal movement, so it can only come on
        // once there is nowhere left to go.
        val hover = arrived
        val collective = collectiveFor(pos.y, desiredY)

        // On station the nose goes to [facing] if there is one; with nothing to face, hold the
        // current heading rather than chase a bearing to the point we are already sitting on.
        val aimAt = if (arrived) facing else target
        val headingError = if (aimAt == null || horizontalDistance(pos, aimAt) < 1.0) {
            0f
        } else {
            Mth.wrapDegrees(yawToward(pos, aimAt) - yaw)
        }
        val yawRate = headingError.coerceIn(-MAX_YAW_RATE, MAX_YAW_RATE)
        val mouseX = yawStick(yawRate, tuning, hover)

        // Level off on arrival; otherwise trade speed error for a nose-down attitude, but only
        // once roughly pointed at the target, or a fast aircraft still turning sails off on an arc.
        val pointed = Mth.abs(headingError) <= THRUST_HEADING_TOLERANCE
        val desiredPitch = if (arrived || !pointed) {
            0f
        } else {
            ((cruiseSpeed - closingSpeed(pos, target, velocity)) * SPEED_TO_PITCH).toFloat()
                .coerceIn(-MAX_PITCH_ANGLE, MAX_PITCH_ANGLE)
        }
        val pitchRate = (desiredPitch - pitch).coerceIn(-MAX_PITCH_RATE, MAX_PITCH_RATE)
        val mouseY = pitchStick(pitchRate, tuning, hover)

        val (rollLeft, rollRight) = rollTrim(roll, rollRate)
        return Command(collective, mouseX, mouseY, hover, rollLeft, rollRight)
    }

    /**
     * Descent for a landing: hold [descentRate] blocks/tick of sink while there is height left,
     * and flare to a hold near the ground so the airframe settles instead of slamming in.
     *
     * @param heightAboveGround blocks between the aircraft and the ground under it
     * @param verticalSpeed     current `deltaMovement.y`, negative while sinking
     */
    fun landingCollective(heightAboveGround: Double, verticalSpeed: Double, descentRate: Double): Collective {
        if (heightAboveGround <= FLARE_HEIGHT) {
            return if (verticalSpeed < -FLARE_RATE) Collective.CLIMB else Collective.SINK_SLOW
        }
        // Only back off the descent when it has run away; anything gentler keeps sinking, because
        // hover mode is on during a landing and its altitude hold would otherwise cancel the
        // descent entirely every time the collective went neutral.
        return if (verticalSpeed < -descentRate * 1.5) Collective.HOLD else Collective.SINK_SLOW
    }

    const val FLARE_HEIGHT = 3.0
    const val FLARE_RATE = 0.12
}
