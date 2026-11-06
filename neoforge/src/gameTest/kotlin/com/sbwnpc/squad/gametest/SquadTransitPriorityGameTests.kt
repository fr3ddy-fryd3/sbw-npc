package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.ai.SquadTransit
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
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
    /** A claim held this long is the man's choice of vehicle, not a passing candidate. */
    private const val CLAIM_HELD_TICKS = 20L

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

    /** Four men held by a role, treatment, cover or a seat stay off the ride; a man under fire boards with a free one. */
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
        drone.claim(NpcActivity.DRONE, this)
        patient.claim(NpcActivity.HEAL, seatOwner)
        covered.claim(NpcActivity.COVER, seatOwner)
        helper.assertTrue(seated.claimAlone(NpcActivity.SEAT, seatOwner), "Could not establish the existing seat owner")
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
            helper.assertTrue(men.take(4).all { it.vehicle == null && VehicleTransportClaims.vehicleOf(it.uuid) == null }, "The ride took a man held by a role, treatment, cover or seat owner")
            if (helper.tick >= 40) {
                helper.assertTrue(control.vehicle === lav, "The free control man did not board")
                // A travelling squad boards under fire (#231, #255): a shot from afar does not end the trip.
                helper.assertTrue(underFire.vehicle === lav, "Recent fire kept a man of a travelling squad from the ride")
                helper.assertTrue(seated.freeFor(NpcActivity.SEAT, seatOwner) && !seated.freeFor(NpcActivity.SEAT, Any()), "Transit replaced the original seat owner")
                scene.result("PASS", "tick=${helper.tick} preserved=4 boarded=2")
                helper.succeed()
            }
        }
    }

    /** A man left behind boards the vehicle his squad already rides in, not a nearer free one (#228, #255). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 200, batch = "transit-left-behind")
    fun manLeftBehindTakesHisSquadsVehicleBeforeANearerFreeOne(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transit-left-behind")
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.VEHICLE, LogGroup.ORDER)
        val manager = SquadManager.get(helper.level)
        val driver = scene.spawn(Vec3(45.5, 1.0, 20.5))
        val straggler = scene.spawn(Vec3(20.5, 1.0, 20.5))
        fun lav(x: Double) =
            requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25)).also {
                val at = helper.absoluteVec(Vec3(x, 1.0, 20.5))
                it.moveTo(at.x, at.y, at.z, 0f, 0f)
                helper.level.addFreshEntity(it)
                Ports.vehicles.fuelAndArm(it, TransportVehicle.LAV_25)
            }
        val squads = lav(45.5)
        val nearer = lav(25.5)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(driver.uuid, straggler.uuid))
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            squads.ejectPassengers()
            squads.discard()
            nearer.discard()
            DebugFlags.enabledGroups = oldGroups
        }
        helper.assertTrue(driver.startRiding(squads, true), "Could not seat the squad's driver")
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(220, 1, 20)))
        // The choice is the claim: the driver may set off before a man 25 blocks away walks up, so
        // waiting for the boarding itself timed the scene out on the ride, not on the choice.
        var claimedSince: Long? = null
        helper.onEachTick {
            helper.assertTrue(VehicleTransportClaims.vehicleOf(straggler.uuid) != nearer.uuid && straggler.vehicle !== nearer, "The man left behind took a nearer free vehicle over his squad's")
            claimedSince = if (VehicleTransportClaims.vehicleOf(straggler.uuid) == squads.uuid) claimedSince ?: helper.tick else null
            val held = claimedSince?.let { helper.tick - it >= CLAIM_HELD_TICKS } == true
            if (straggler.vehicle === squads || held) {
                scene.result("PASS", "tick=${helper.tick} boarded=${straggler.vehicle === squads} claimedSince=$claimedSince")
                helper.succeed()
            }
        }
    }
}
