package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.ai.SquadTransit
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

/** Ordinary transit must respect the same role, treatment, cover and seat owners in Core. */
@PrefixGameTestTemplate(false)
object SquadTransitPriorityGameTests {
    /** A nearby enemy still belongs to combat, even while the objective is far enough to ride. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 160, batch = "transit-close-contact")
    fun nearbyContactStillInterruptsDistantTransport(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transit-close-contact")
        val manager = SquadManager.get(helper.level)
        val man = scene.spawn(Vec3(20.5, 1.0, 20.5))
        val enemy = scene.enemy(Vec3(50.5, 1.0, 20.5))
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(26.5, 1.0, 20.5))
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        Ports.vehicles.fuelAndArm(lav, TransportVehicle.LAV_25)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        BrainUtils.setTargetOfEntity(man, enemy)
        helper.onEachTick {
            BrainUtils.setTargetOfEntity(man, enemy)
            helper.assertTrue(!SquadTransit.marching(man), "The nearby enemy was mistaken for a remote remembered contact")
            helper.assertTrue(VehicleTransportClaims.driverOf(lav.uuid) != man.uuid, "Transport took the wheel away from an active nearby fight")
            if (helper.tick >= 60) {
                scene.result("PASS", "tick=${helper.tick} combatTarget=${man.target?.uuid}")
                helper.succeed()
            }
        }
    }

    /** Five occupied men stay off the ride while an otherwise identical free man boards. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 160, batch = "transit-priorities")
    fun distantTransitRespectsRoleTreatmentCoverAndSeatOwners(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transit-priorities")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 6).map { scene.spawn(Vec3(20.5 + it * 2, 1.0, 20.5)) }
        val drone = men[0]
        val patient = men[1]
        val seated = men[2]
        val covered = men[3]
        val underFire = men[4]
        val control = men.last()
        val seatOwner = Any()
        drone.operatingDrone = true
        BrainUtils.setMemory(patient, ModMemories.MEDIC_HEALING.get(), true)
        BrainUtils.setMemory(covered, ModMemories.COVER_HOLD.get(), true)
        helper.assertTrue(seated.claimSeat(seatOwner), "Could not establish the existing seat owner")
        seated.claim(NpcActivity.VEHICLE, seatOwner)
        underFire.incomingFire.record(underFire.eyePosition, Vec3(1.0, 0.0, 0.0), null, helper.level.gameTime)
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(35.5, 1.0, 20.5))
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        Ports.vehicles.fuelAndArm(lav, TransportVehicle.LAV_25)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            seated.release(NpcActivity.SEAT, seatOwner)
            seated.release(NpcActivity.VEHICLE, seatOwner)
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        helper.onEachTick {
            helper.assertTrue(men.take(5).all { it.vehicle == null && VehicleTransportClaims.vehicleOf(it.uuid) == null }, "The ride took a man held by a role, treatment, cover, seat owner or recent fire")
            if (helper.tick >= 40) {
                helper.assertTrue(control.vehicle === lav, "The free control man did not board")
                helper.assertTrue(seated.seatFreeFor(seatOwner) && !seated.seatFreeFor(Any()), "Transit replaced the original seat owner")
                helper.assertTrue(underFire.incomingFire.recentPoint(helper.level.gameTime) != null, "The incoming-fire fixture expired before the boarding check")
                scene.result("PASS", "tick=${helper.tick} preserved=5 boarded=1")
                helper.succeed()
            }
        }
    }
}
