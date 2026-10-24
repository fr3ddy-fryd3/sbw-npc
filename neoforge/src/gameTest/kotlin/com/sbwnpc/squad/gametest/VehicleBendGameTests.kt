package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import kotlin.math.abs

/** A real LAV follows an L-shaped corridor past an inside-corner obstruction, and turns back on itself. */
@PrefixGameTestTemplate(false)
object VehicleBendGameTests {
    // Blocks the LAV may back while turning round: its arc there is about 25, the reported fault 50.
    private const val MAX_BACKED = 35.0

    private fun corridor(helper: GameTestHelper) {
        val walls = (10..104).map { BlockPos(26, 1, it) } +
            (10..96).map { BlockPos(34, 1, it) } +
            (26..165).map { BlockPos(it, 1, 104) } +
            (34..165).map { BlockPos(it, 1, 96) } +
            (26..34).map { BlockPos(it, 1, 10) } +
            listOf(BlockPos(33, 1, 96), BlockPos(33, 1, 97))
        // SBW can pitch a long hull up a low wall; the route must go around this corner.
        for (base in walls) for (rise in 0..11) helper.setBlock(base.above(rise), Blocks.BEDROCK)
    }

    /** The hull must leave the bend behind rather than retrying the same collision for a minute. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "vehicle-bend")
    fun lavDrivesPastAnInsideCornerWithoutRepeatedCollisions(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-bend")
        DebugFlags.enabledGroups += LogGroup.VEHICLE
        corridor(helper)
        val manager = SquadManager.get(helper.level)
        val man = scene.spawn(Vec3(30.5, 1.0, 20.5))
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(30.5, 1.0, 20.5))
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        Ports.vehicles.fuelAndArm(lav, TransportVehicle.LAV_25)
        man.random.setSeed(152L)
        man.assignedVehicleId = lav.uuid
        helper.assertTrue(man.startRiding(lav, true), "The driver could not board the LAV")
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, helper.absolutePos(BlockPos(150, 1, 100)))
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        var enteredBendAt: Long? = null
        helper.onEachTick {
            val here = helper.relativeVec(lav.position())
            if (enteredBendAt == null && here.z >= 80) enteredBendAt = helper.tick
            val inBend = enteredBendAt?.let { helper.tick - it } ?: 0
            // The inside wall ends at x=34; by x=42 the full eight-block hull is past it.
            if (inBend > 300) helper.assertTrue(here.x >= 42, "The LAV kept retrying the same bend for fifteen seconds: $here")
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} position=$here speed=${lav.deltaMovement.horizontalDistance()}")
            if (helper.tick >= 1000) helper.assertTrue(here.x >= 80, "The LAV repeatedly collided with the inside of the bend: $here")
            if (here.x >= 128 && abs(here.z - 100.5) < 8) {
                helper.assertTrue(man.isAlive && man.vehicle === lav, "The driver abandoned the trip")
                scene.result("PASS", "tick=${helper.tick} position=$here")
                helper.succeed()
            }
        }
    }

    /** A slope rising one block every eight towards +z: a hull backing down it gathers speed, and still has room to back. */
    private fun slope(helper: GameTestHelper): (Double) -> Int {
        val height = { z: Double -> ((z - 20.0) / 8.0).toInt().coerceAtLeast(0) }
        for (x in 30..130) for (z in 20..125) for (y in 1..height(z.toDouble())) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        return height
    }

    /**
     * Sent to a point down a slope straight behind it, the LAV turns round and drives there. In-game
     * 23.10 one backing round downhill gathered speed, chattered between backing and creeping forward
     * at the reverse-turn angle, and reversed about 50 blocks without turning: the brake key sped up
     * a hull already rolling backwards.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1000, batch = "vehicle-bend")
    fun lavTurnsRoundDownhillInsteadOfReversingThere(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-turn-round")
        DebugFlags.enabledGroups += LogGroup.VEHICLE
        val height = slope(helper)
        val manager = SquadManager.get(helper.level)
        val start = Vec3(80.5, height(110.0) + 1.0, 110.5)
        val man = scene.spawn(start)
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(start)
        // yRot 0 faces +z, up the slope; the objective lies down it, at -z.
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        Ports.vehicles.fuelAndArm(lav, TransportVehicle.LAV_25)
        man.assignedVehicleId = lav.uuid
        helper.assertTrue(man.startRiding(lav, true), "The driver could not board the LAV")
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, helper.absolutePos(BlockPos(80, 1, 5)))
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        // Ground covered backwards: the turn needs about 25 blocks of it on the LAV's circle, the
        // reported fault ran 50, and a chatter at the backing exit keeps rolling back too.
        var backed = 0.0
        var last = lav.position()
        helper.onEachTick {
            val here = helper.relativeVec(lav.position())
            val step = lav.position().subtract(last)
            last = lav.position()
            val facing = lav.getViewVector(1f)
            if (step.x * facing.x + step.z * facing.z < 0.0) backed += step.horizontalDistance()
            helper.assertTrue(backed <= MAX_BACKED, "The LAV backed $backed blocks without turning round: $here yRot=${lav.yRot}")
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} position=$here yRot=${lav.yRot} backed=$backed")
            if (here.z <= 15.0) {
                helper.assertTrue(man.isAlive && man.vehicle === lav, "The driver abandoned the trip")
                scene.result("PASS", "tick=${helper.tick} position=$here backed=$backed")
                helper.succeed()
            }
        }
    }
}
