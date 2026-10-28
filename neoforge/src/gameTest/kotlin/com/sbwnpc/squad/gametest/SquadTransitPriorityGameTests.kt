package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.HostileMobs
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.MovementClaim
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
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Zombie
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

    /** Four occupied men stay off the ride while an otherwise identical free man boards, and one under fire boards too: the vehicle is the cover (#198). */
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
        patient.claimMovement(seatOwner, MovementClaim.HEAL)
        covered.claimMovement(seatOwner, MovementClaim.COVER)
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
            helper.assertTrue(men.take(4).all { it.vehicle == null && VehicleTransportClaims.vehicleOf(it.uuid) == null }, "The ride took a man held by a role, treatment, cover or seat owner")
            if (helper.tick >= 40) {
                helper.assertTrue(control.vehicle === lav, "The free control man did not board")
                helper.assertTrue(underFire.vehicle === lav, "The man under fire did not run to the vehicle")
                helper.assertTrue(seated.seatFreeFor(seatOwner) && !seated.seatFreeFor(Any()), "Transit replaced the original seat owner")
                helper.assertTrue(underFire.incomingFire.recentPoint(helper.level.gameTime) != null, "The incoming-fire fixture expired before the boarding check")
                scene.result("PASS", "tick=${helper.tick} preserved=4 boarded=2")
                helper.succeed()
            }
        }
    }

    /** A sturdy mob that cannot move: it is only there to be a threat to whoever the scene points it at. */
    private fun zombieAt(helper: GameTestHelper, at: Vec3): Zombie =
        EntityType.ZOMBIE.create(helper.level)!!.also {
            val point = helper.absoluteVec(at)
            it.moveTo(point.x, point.y, point.z, 0f, 0f)
            it.isNoAi = true
            it.getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 1000.0
            it.health = 1000f
            helper.level.addFreshEntity(it)
        }

    /** The squad takes its ride only once no mob that is after one of them is within 16 blocks of any of them (#198). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "transport-mob-close")
    fun squadTakesItsRideOnlyOnceNoMobAfterOneOfThemIsClose(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-mob-close")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = SquadTransportGameTests.park(helper, Vec3(45.5, 1.0, 20.5))
        val zombie = zombieAt(helper, Vec3(24.5, 1.0, 20.5))
        // After him from the first tick: a decision taken before the mob has a target would see no threat.
        zombie.target = men.first()
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
            zombie.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        var decided = false
        helper.onEachTick {
            zombie.target = men.first()
            val claimed = men.any { it.vehicleTransport }
            if (claimed && !decided) {
                decided = true
                val nearest = men.minOf { zombie.distanceTo(it) }
                helper.assertTrue(nearest > HostileMobs.CLOSE_RANGE - 0.5, "The squad took its ride with a mob after it ${"%.1f".format(nearest)} blocks from one of them")
            }
            if (men.all { it.vehicle === lav }) {
                scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** A mob further than 16 blocks is not close combat: the squad boards, the hunted man too (#198). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "transport-mob-far")
    fun mobBeyondSixteenBlocksDoesNotKeepAManFromTheRide(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-mob-far")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = SquadTransportGameTests.park(helper, Vec3(45.5, 1.0, 20.5))
        val zombie = zombieAt(helper, Vec3(4.5, 1.0, 20.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
            zombie.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        helper.onEachTick {
            zombie.target = men.first()
            if (men.all { it.vehicle === lav }) {
                scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** Once the squad travels the vehicle is the cover: a hit man boards instead of running for cover (#198). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "transport-hit-man")
    fun hitManBoardsInsteadOfRunningForCover(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-hit-man")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = SquadTransportGameTests.park(helper, Vec3(45.5, 1.0, 20.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        val hit = men.first()
        helper.onEachTick {
            if (helper.tick < 120) hit.suppress(helper.absoluteVec(Vec3(10.5, 1.0, 20.5)))
            helper.assertTrue(!hit.movementLockedByCover(), "The man who was hit went for cover instead of the ride")
            if (men.all { it.vehicle === lav }) {
                scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** A man left behind rejoins the vehicle his squad has claimed before a free one that is nearer to him (#198). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "transport-left-behind")
    fun manLeftBehindTakesHisSquadsVehicleBeforeANearerFreeOne(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-left-behind")
        val manager = SquadManager.get(helper.level)
        val men = listOf(40.5, 41.5, 42.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val late = scene.spawn(Vec3(20.5, 1.0, 20.5))
        val squadsLav = SquadTransportGameTests.park(helper, Vec3(45.5, 1.0, 20.5))
        val freeLav = SquadTransportGameTests.park(helper, Vec3(24.5, 1.0, 22.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, (men + late).map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            squadsLav.discard()
            freeLav.discard()
        }
        // He is busy until his squad has taken its vehicle, as one left behind is.
        late.resupplying = true
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        helper.onEachTick {
            if (helper.tick == 60L) {
                helper.assertTrue(VehicleTransportClaims.vehicleOf(men.first().uuid) == squadsLav.uuid, "His squad had not claimed its vehicle yet")
                late.resupplying = false
            }
            val claimed = VehicleTransportClaims.vehicleOf(late.uuid)
            if (helper.tick > 60 && claimed != null) {
                helper.assertTrue(claimed == squadsLav.uuid, "He took the free vehicle beside him instead of his squad's")
                helper.succeed()
            }
        }
    }
}
