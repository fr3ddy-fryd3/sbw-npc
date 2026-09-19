package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.ai.HelicopterFlightController.Collective
import com.sbwnpc.squad.entity.ai.HelicopterFlightController.Tuning
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class HelicopterFlightControllerTest {
    private val origin = Vec3(0.0, 80.0, 0.0)
    /** Rotor spun up to roughly the hover point, Mi-28 engine tuning. */
    private val cruising = Tuning(authority = 0.09f, yawSpeed = 0.85f, pitchSpeed = 0.75f)

    @Test
    fun `yaw stick inverts the engine's own yaw formula`() {
        val stick = HelicopterFlightController.yawStick(3f, cruising, hoverMode = false)
        // Replay what VehicleEngineUtils.helicopterEngine does with it.
        val applied = cruising.yawSpeed * Mth.clamp(2f * stick * cruising.authority, -10f, 10f)
        assertEquals(3f, applied, 1e-3f)
    }

    @Test
    fun `sticks account for hover mode pre-scaling the engine speeds`() {
        val yaw = HelicopterFlightController.yawStick(3f, cruising, hoverMode = true)
        val appliedYaw = (cruising.yawSpeed * 0.5f) * Mth.clamp(2f * yaw * cruising.authority, -10f, 10f)
        assertEquals(3f, appliedYaw, 1e-3f)

        val pitch = HelicopterFlightController.pitchStick(1f, cruising, hoverMode = true)
        val appliedPitch = 1.5f * (cruising.pitchSpeed * 0.2f) * pitch * cruising.authority
        assertEquals(1f, appliedPitch, 1e-3f)
    }

    @Test
    fun `no stick input while the rotor is still spinning up`() {
        val cold = Tuning(authority = 0.001f, yawSpeed = 0.85f, pitchSpeed = 0.75f)
        assertEquals(0f, HelicopterFlightController.yawStick(5f, cold, hoverMode = false))
        assertEquals(0f, HelicopterFlightController.pitchStick(2f, cold, hoverMode = false))
    }

    @Test
    fun `collective bands - climb, hold, gentle sink, fast sink`() {
        assertEquals(Collective.CLIMB, HelicopterFlightController.collectiveFor(80.0, 90.0))
        assertEquals(Collective.HOLD, HelicopterFlightController.collectiveFor(80.0, 80.5))
        assertEquals(Collective.SINK_SLOW, HelicopterFlightController.collectiveFor(85.0, 80.0))
        assertEquals(Collective.SINK_FAST, HelicopterFlightController.collectiveFor(120.0, 80.0))
    }

    @Test
    fun `turns before accelerating instead of arcing off`() {
        val target = Vec3(0.0, 80.0, 200.0) // due south, yaw 0
        val facingAway = HelicopterFlightController.steer(
            origin, yaw = 180f, pitch = 0f, roll = 0f, rollRate = 0f, velocity = Vec3.ZERO,
            target = target, desiredY = 80.0, cruiseSpeed = 0.8, tuning = cruising
        )
        // Pointed the wrong way: no nose-down command yet, but the stick is turning us around.
        assertEquals(0f, facingAway.mouseY, 1e-6f)
        assertTrue(kotlin.math.abs(facingAway.mouseX) > 0f)

        val pointed = HelicopterFlightController.steer(
            origin, yaw = 0f, pitch = 0f, roll = 0f, rollRate = 0f, velocity = Vec3.ZERO,
            target = target, desiredY = 80.0, cruiseSpeed = 0.8, tuning = cruising
        )
        assertTrue(pointed.mouseY > 0f, "should command nose-down to accelerate")
    }

    @Test
    fun `hover mode only engages on arrival`() {
        val far = HelicopterFlightController.steer(
            origin, 0f, 0f, 0f, 0f, Vec3(0.0, 0.0, 0.5), Vec3(0.0, 80.0, 100.0), 80.0, 0.8, cruising
        )
        assertFalse(far.hoverMode)

        val onStation = HelicopterFlightController.steer(
            origin, 0f, 0f, 0f, 0f, Vec3(0.0, 0.0, 0.05), Vec3(0.0, 80.0, 2.0), 80.0, 0.8, cruising
        )
        assertTrue(onStation.hoverMode)
    }

    @Test
    fun `landing arrests an excessive sink rate and flares near the ground`() {
        assertEquals(Collective.HOLD, HelicopterFlightController.landingCollective(20.0, -0.9, 0.2))
        assertEquals(Collective.SINK_SLOW, HelicopterFlightController.landingCollective(20.0, -0.05, 0.2))
        // Close in and still dropping fast: add power rather than ride it into the deck.
        assertEquals(Collective.CLIMB, HelicopterFlightController.landingCollective(2.0, -0.5, 0.2))
    }

    @Test
    fun `roll trim pushes the wings back toward level`() {
        // Left pedal reduces roll, right increases it.
        assertEquals(true to false, HelicopterFlightController.rollTrim(9f))
        // Banked, but the rate already stored will land it level: stop pushing instead of driving
        // straight through level and having to correct back the other way.
        assertEquals(false to false, HelicopterFlightController.rollTrim(9f, -0.9f))
        // Coming back too fast to stop at level — catch it with the opposite pedal.
        assertEquals(false to true, HelicopterFlightController.rollTrim(9f, -2f))
        assertEquals(false to true, HelicopterFlightController.rollTrim(-9f))
        assertEquals(false to false, HelicopterFlightController.rollTrim(0.5f))
    }

    @Test
    fun `sliding backwards is answered with nose down, not more nose up`() {
        val target = Vec3(0.0, 80.0, 200.0) // due south, yaw 0
        // Drifting AWAY from the target at a fair clip while still pointed at it. Judged by
        // unsigned speed this reads as "too fast, brake", and braking is nose-up, which tilts the
        // lift vector further backwards — the aircraft then reverses away indefinitely.
        val reversing = HelicopterFlightController.steer(
            origin, yaw = 0f, pitch = 0f, roll = 0f, rollRate = 0f, velocity = Vec3(0.0, 0.0, -0.9),
            target = target, desiredY = 80.0, cruiseSpeed = 0.35, tuning = cruising
        )
        assertTrue(reversing.mouseY > 0f, "must command nose-down to stop going backwards")
    }

    @Test
    fun `closing speed is signed along the line to the target`() {
        val target = Vec3(0.0, 80.0, 100.0)
        assertEquals(0.5, HelicopterFlightController.closingSpeed(origin, target, Vec3(0.0, 0.0, 0.5)), 1e-9)
        assertEquals(-0.5, HelicopterFlightController.closingSpeed(origin, target, Vec3(0.0, 0.0, -0.5)), 1e-9)
        // Pure sideways drift is neither closing nor falling behind.
        assertEquals(0.0, HelicopterFlightController.closingSpeed(origin, target, Vec3(0.7, 0.0, 0.0)), 1e-9)
    }

    @Test
    fun `recovers from being thrown backwards at speed`() {
        val sim = Heli(pos = Vec3(0.0, 90.0, 0.0), yaw = 0f)
        sim.motion = Vec3(0.0, 0.0, -1.2)
        val target = Vec3(0.0, 0.0, 120.0)
        var worstRetreat = 0.0
        repeat(1200) {
            val cmd = HelicopterFlightController.steer(
                sim.pos, sim.yaw, sim.pitch, sim.roll, sim.rollRate(), sim.motion,
                target, 90.0, 0.8, sim.tuning()
            )
            sim.step(cmd)
            worstRetreat = minOf(worstRetreat, sim.pos.z)
        }
        assertTrue(worstRetreat > -80.0, "ran away backwards to z=$worstRetreat before recovering")
        val flat = Math.hypot(target.x - sim.pos.x, target.z - sim.pos.z)
        assertTrue(flat < 10.0, "should have come back and stopped on station, was $flat away")
    }

    @Test
    fun `a long turning flight does not wind up the bank angle`() {
        // The yaw stick feeds the engine's roll term, and nothing in the piloted branch restores
        // roll on its own, so a sustained turn used to tip the aircraft further and further over
        // until it slid sideways. Fly a course that turns the whole way and check the wings.
        val sim = Heli(pos = Vec3(0.0, 90.0, 0.0), yaw = 0f)
        var worst = 0f
        repeat(900) {
            val cmd = HelicopterFlightController.steer(
                sim.pos, sim.yaw, sim.pitch, sim.roll, sim.rollRate(), sim.motion,
                Vec3(-150.0, 0.0, -150.0), 90.0, 0.8, sim.tuning()
            )
            sim.step(cmd)
            worst = maxOf(worst, kotlin.math.abs(sim.roll))
        }
        assertTrue(worst < 12f, "bank angle ran away to $worst degrees")
    }

    /**
     * Closed loop against a plant that mirrors the real `helicopterEngine` maths, to prove the
     * controller actually converges instead of oscillating — the thing unit-testing the formulas
     * one at a time cannot show.
     */
    @Test
    fun `flies to a distant station and settles there`() {
        val sim = Heli(pos = Vec3(0.0, 80.0, 0.0), yaw = 180f)
        val target = Vec3(120.0, 0.0, 90.0)
        repeat(1200) {
            val cmd = HelicopterFlightController.steer(
                sim.pos, sim.yaw, sim.pitch, sim.roll, sim.rollRate(), sim.motion, target, 95.0, 0.8, sim.tuning()
            )
            sim.step(cmd)
        }
        val flat = Math.hypot(target.x - sim.pos.x, target.z - sim.pos.z)
        assertTrue(flat < 8.0, "should be on station, was $flat blocks away")
        assertTrue(kotlin.math.abs(sim.pos.y - 95.0) < 6.0, "should hold altitude, was ${sim.pos.y}")
        assertTrue(sim.horizontalSpeed() < 0.25, "should have settled, speed ${sim.horizontalSpeed()}")
        assertTrue(kotlin.math.abs(sim.pitch) < 30f, "attitude should stay sane, was ${sim.pitch}")
        assertTrue(kotlin.math.abs(sim.roll) < 15f, "wings should be near level, roll ${sim.roll}")
    }

    @Test
    fun `climbs to altitude from a standing start on the ground`() {
        val sim = Heli(pos = Vec3(0.0, 64.0, 0.0), yaw = 0f)
        repeat(400) {
            val cmd = HelicopterFlightController.steer(
                sim.pos, sim.yaw, sim.pitch, sim.roll, sim.rollRate(), sim.motion, Vec3(0.0, 0.0, 0.0), 84.0, 0.8, sim.tuning()
            )
            sim.step(cmd)
        }
        assertTrue(kotlin.math.abs(sim.pos.y - 84.0) < 6.0, "expected to reach hover altitude, at ${sim.pos.y}")
    }

    /** Minimal model of VehicleEngineUtils.helicopterEngine — only the parts the controller drives. */
    private class Heli(var pos: Vec3, var yaw: Float) {
        var pitch = 0f
        var roll = 0f
        private var previousRoll = 0f
        var power = 0f
        var propellerRot = 0f
        var motion = Vec3.ZERO
        private var holdPowerTick = 0
        private var holdTick = 0
        private var deltaRot = 0f

        private val increment = 0.8f
        private val decrement = 0.8f
        private val liftSpeed = 1f
        private val yawSpeed = 0.85f
        private val pitchSpeed = 0.75f
        private val rollSpeed = 0.6f

        fun tuning() = Tuning(propellerRot, yawSpeed, pitchSpeed)

        fun horizontalSpeed(): Double = Math.hypot(motion.x, motion.z)

        fun rollRate(): Float {
            val rate = roll - previousRoll
            previousRoll = roll
            return rate
        }

        fun step(cmd: HelicopterFlightController.Command) {
            val engineStartOver = power > 0.04f
            when (cmd.collective) {
                Collective.CLIMB -> {
                    holdPowerTick++
                    power = if (engineStartOver) {
                        minOf(power + 0.0007f * increment * minOf(holdPowerTick, 10), 0.12f)
                    } else {
                        minOf(power + 0.0012f * increment, 0.045f)
                    }
                }
                Collective.SINK_SLOW -> {
                    holdPowerTick++
                    if (engineStartOver) {
                        power = maxOf(power - 0.001f * decrement * minOf(holdPowerTick, 5), 0.058f / liftSpeed)
                    }
                }
                Collective.SINK_FAST -> {
                    holdPowerTick++
                    if (engineStartOver) {
                        power = maxOf(power - 0.001f * decrement * minOf(holdPowerTick, 5), 0.035f / liftSpeed)
                    }
                }
                Collective.HOLD -> {
                    holdPowerTick = 0
                    if (engineStartOver) {
                        val force = (if (cmd.hoverMode) 0.01f else 0.002f) * motion.y.toFloat()
                        power = if (motion.y < 0) minOf(power - force, 0.12f) else maxOf(power - force, 0f)
                    }
                }
            }

            if (cmd.rollRight) {
                holdTick++
                deltaRot -= 1f * minOf(holdTick, 7) * power
            } else if (cmd.rollLeft) {
                holdTick++
                deltaRot += 1f * minOf(holdTick, 7) * power
            } else {
                holdTick = 0
            }

            val effYaw = yawSpeed * if (cmd.hoverMode) 0.5f else 1f
            val effPitch = pitchSpeed * if (cmd.hoverMode) 0.2f else 1f
            val effRoll = rollSpeed * if (cmd.hoverMode) 0.05f else 1f
            yaw += effYaw * Mth.clamp(2f * cmd.mouseX * propellerRot, -10f, 10f)
            pitch += 1.5f * effPitch * cmd.mouseY * propellerRot
            // The yaw stick feeds the roll term too — this is what used to bank the aircraft
            // further on every single turn, with nothing to bring it back.
            roll -= effRoll * (deltaRot + 0.25f * cmd.mouseX * propellerRot)
            if (cmd.hoverMode) {
                pitch *= 0.97f
                roll *= 0.97f
            }
            deltaRot *= 0.9f

            propellerRot = Mth.lerp(0.18f, propellerRot, power)

            val y = Math.toRadians(yaw.toDouble())
            val p = Math.toRadians(pitch.toDouble())
            val r = Math.toRadians(roll.toDouble())
            // Entity up vector for this attitude: nose down (positive pitch) tilts lift forward,
            // and bank tilts it sideways — which is how a rolled airframe slides across the ground.
            val upLevel = Vec3(-sin(y) * sin(p), cos(p), cos(y) * sin(p))
            // SBW's getRightVec is the NEGATED local X axis (VehicleVecUtils.getRightVec), so at
            // yaw 0 right is -X. With the sign the other way round the bank feedback comes out
            // self-correcting and this model quietly stops reproducing anything.
            val right = Vec3(-cos(y), 0.0, -sin(y))
            val up = upLevel.scale(cos(r)).add(right.scale(sin(r)))
            motion = motion.add(up.scale((propellerRot * liftSpeed * 0.66f).toDouble()))
            motion = motion.add(0.0, -0.06, 0.0)
            val drag = if (cmd.hoverMode) 0.95 else 0.985
            motion = Vec3(motion.x * drag, motion.y * 0.95, motion.z * drag)
            pos = pos.add(motion)
        }
    }
}
