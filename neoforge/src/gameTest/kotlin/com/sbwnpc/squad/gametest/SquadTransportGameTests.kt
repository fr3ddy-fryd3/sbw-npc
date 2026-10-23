package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** A distant ATTACK travels as a squad before the operation hands out position work. */
@PrefixGameTestTemplate(false)
object SquadTransportGameTests {
    private fun park(helper: GameTestHelper, at: Vec3): Entity =
        requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25)).also {
            val point = helper.absoluteVec(at)
            it.moveTo(point.x, point.y, point.z, 0f, 0f)
            helper.level.addFreshEntity(it)
            Ports.vehicles.fuelAndArm(it, TransportVehicle.LAV_25)
        }

    /** The fifth man is personally within 100 blocks, but his squad and its ride are still far. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "whole-squad-transport")
    fun allFiveAttackersBoardBeforeTakingPositionDuties(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "whole-squad-transport")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5, 68.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = park(helper, Vec3(45.5, 1.0, 20.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        helper.onEachTick {
            if (helper.tick == 40L) helper.assertTrue(men.none(SquadTactics::hasTask), "A distant attacker was given a position duty instead of the squad's ride")
            if (men.all { it.vehicle === lav }) {
                scene.result("PASS", "tick=${helper.tick} aboard=${men.size}")
                helper.succeed()
            }
        }
    }

    /** A man outside the vehicle search radius walks instead of making his own trip to the ride. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "transport-straggler")
    fun memberBeyondVehicleSearchRadiusWalksWhileTheOthersBoard(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-straggler")
        val manager = SquadManager.get(helper.level)
        val near = (0 until 4).map { scene.spawn(Vec3(20.5 + it * 2, 1.0, 20.5)) }
        val straggler = scene.spawn(Vec3(100.5, 1.0, 20.5))
        val start = straggler.position()
        val lav = park(helper, Vec3(28.5, 1.0, 20.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, (near + straggler).map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(200, 1, 20)))
        helper.onEachTick {
            helper.assertTrue(straggler.vehicle == null && VehicleTransportClaims.vehicleOf(straggler.uuid) == null, "The distant straggler was sent back to the vehicle")
            if (helper.tick >= 180) {
                helper.assertTrue(near.all { it.vehicle === lav }, "The nearby squad members did not all board")
                helper.assertTrue(straggler.x > start.x + 8.0, "The straggler did not continue towards the objective on foot")
                scene.result("PASS", "tick=${helper.tick} aboard=${near.size} walked=${straggler.x - start.x}")
                helper.succeed()
            }
        }
    }
}
