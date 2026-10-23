package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTransit
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

/** A distant ATTACK travels as a squad before the operation hands out position work. */
@PrefixGameTestTemplate(false)
object SquadTransportGameTests {
    /** Observes physical march while far; after transit the reinforcement takes its entry post. */
    internal fun reinforcementMarch(helper: GameTestHelper, group: List<NpcEntity>, squad: Squad, entry: Point3, elapsed: Long): Boolean {
        val transit = SquadTransit.distant(squad, helper.level)
        val astray = group.filter { npc ->
            NativeTactics.assignment(npc).let { task ->
                if (transit) task != null else task == null || task.anchor.distanceTo(Vec3(entry.x, entry.y, entry.z)) > 1.0
            }
        }
        helper.assertTrue(astray.isEmpty(), "Distant reinforcement is not marching to the entry: ${astray.map { helper.relativeVec(it.position()) to NativeTactics.assignment(it) }}")
        val moved = group.any { helper.relativeVec(it.position()).x > 7.5 }
        if (elapsed > 120) helper.assertTrue(moved, "Distant reinforcement did not physically start its ordinary march")
        return moved
    }

    private fun park(helper: GameTestHelper, at: Vec3): Entity =
        requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25)).also {
            val point = helper.absoluteVec(at)
            it.moveTo(point.x, point.y, point.z, 0f, 0f)
            helper.level.addFreshEntity(it)
            Ports.vehicles.fuelAndArm(it, TransportVehicle.LAV_25)
        }

    private fun keepContact(men: List<NpcEntity>, enemy: NpcEntity?) {
        if (enemy == null) return
        men.forEach { BrainUtils.setTargetOfEntity(it, enemy) }
    }

    private fun assertTransportDriver(helper: GameTestHelper, lav: Entity) {
        val driver = VehicleTransportClaims.driverOf(lav.uuid)
        helper.assertTrue(driver != null && Ports.vehicles.seating(lav).firstOrNull()?.uuid == driver, "The boarded squad has no transport driver at the wheel")
    }

    /** The fifth man is personally within 100 blocks, but his squad and its ride are still far. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "whole-squad-transport")
    fun allFiveAttackersBoardBeforeTakingPositionDuties(helper: GameTestHelper) = wholeSquadBoards(helper)

    /** A reported target beyond engagement distance does not switch off the common ride. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "remote-contact-transport")
    fun allFiveAttackersBoardWhileKeepingARemoteContact(helper: GameTestHelper) = wholeSquadBoards(helper, remoteContact = true)

    private fun wholeSquadBoards(helper: GameTestHelper, remoteContact: Boolean = false) {
        val scene = CombatIdleGameTests.Scene(helper, if (remoteContact) "remote-contact-transport" else "whole-squad-transport")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5, 68.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = park(helper, Vec3(45.5, 1.0, 20.5))
        val parkedAt = lav.position()
        val enemy = if (remoteContact) scene.enemy(Vec3(150.5, 1.0, 20.5)) else null
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        keepContact(men, enemy)
        var retainedContactTicks = 0
        helper.onEachTick {
            if (enemy != null && men.any { it.target === enemy }) retainedContactTicks++
            keepContact(men, enemy)
            if (helper.tick == 40L) helper.assertTrue(men.none(SquadTactics::hasTask), "A distant attacker was given a position duty instead of the squad's ride")
            if (men.any { it.vehicle === lav } && men.any { it.vehicle !== lav }) {
                helper.assertTrue(lav.position().distanceTo(parkedAt) <= 4.0, "The driver left before the nearby men with remote contacts boarded")
            }
            if (men.all { it.vehicle === lav }) {
                assertTransportDriver(helper, lav)
                helper.assertTrue(!remoteContact || retainedContactTicks >= 5, "The remote contact was cleared instead of retained during boarding")
                scene.result("PASS", "tick=${helper.tick} aboard=${men.size} retainedContactTicks=$retainedContactTicks")
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
