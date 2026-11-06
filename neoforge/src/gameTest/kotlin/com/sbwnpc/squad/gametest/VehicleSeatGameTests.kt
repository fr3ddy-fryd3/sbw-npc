package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.VehicleModel
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleTransportBehaviour
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.integration.sbw.emptyVehicleCargo
import com.sbwnpc.squad.integration.sbw.overheatVehicleGun
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
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

/**
 * Who gets an NPC into a ground vehicle and out again: a squad's ride to a far objective and a
 * rifleman taking a free vehicle's gun against armour (#136). Helicopters have their own scenes
 * in [HelicopterOrbitGameTests].
 */
@PrefixGameTestTemplate(false)
object VehicleSeatGameTests {
    private fun park(helper: GameTestHelper, model: VehicleModel, at: Vec3): Entity =
        requireNotNull(Ports.vehicles.create(helper.level, model)).also {
            val point = helper.absoluteVec(at)
            it.moveTo(point.x, point.y, point.z, 0f, 0f)
            helper.level.addFreshEntity(it)
            Ports.vehicles.fuelAndArm(it, model)
        }

    private fun arrivalSeatsCleared(helper: GameTestHelper, lav: Entity, men: List<NpcEntity>, lostSightAt: Int): Boolean {
        val gunner = men.first()
        when {
            helper.tick < 2 -> helper.assertTrue(men.all { it.vehicle === lav }, "ATTACK stopped to fight before arrival")

            helper.tick < 60 -> {
                helper.assertTrue(men.drop(1).all { it.vehicle == null }, "The passengers did not dismount on arrival")
                helper.assertTrue(gunner.vehicle === lav, "The gunner abandoned the turret on arrival with contact")
                helper.assertTrue(VehicleTransportClaims.combatGunnerOf(lav.uuid) == gunner.uuid, "The arriving gunner lost his seat claim")
            }

            gunner.tickCount - lostSightAt < 199 -> helper.assertTrue(gunner.vehicle === lav, "The gunner left before ten seconds without contact")

            gunner.vehicle == null -> return true
        }
        return false
    }

    private fun boardArrivalCrew(helper: GameTestHelper, scene: CombatIdleGameTests.Scene, lav: Entity): List<NpcEntity> {
        val men = (0 until 3).map { scene.spawn(Vec3(20.5 + it * 2, 1.0, 20.5)).also { it.isNoAi = true } }
        val gunner = men.first()
        // LAV-25 combines the wheel and turret in seat zero.
        helper.assertTrue(gunner.startRiding(lav, true) && Ports.vehicles.hasWeaponAt(lav, gunner), "The gunner could not take the armed driving seat")
        men.drop(1).forEach {
            helper.assertTrue(it.startRiding(lav, true) && !Ports.vehicles.hasWeaponAt(lav, it), "The passenger needs an unarmed seat")
        }
        return men
    }

    /** A squad sent more than 100 blocks off takes the vehicle parked by it, and gets out at the far end. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 3000, batch = "vehicle-ride")
    fun squadRidesAParkedVehicleToAFarObjectiveAndGetsOut(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-ride")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 4).map { scene.spawn(Vec3(14.5 + it * 2, 1.0, 14.5)) }
        val lav = park(helper, TransportVehicle.LAV_150, Vec3(22.5, 1.0, 22.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        val objective = helper.absolutePos(BlockPos(150, 1, 200))
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, objective)
        var boarded = false
        helper.onEachTick {
            val aboard = men.count { it.vehicle === lav }
            val near = men.count { it.vehicle == null && it.position().distanceTo(Vec3.atCenterOf(objective)) <= 40 }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} aboard=$aboard near=$near lav=${helper.relativeVec(lav.position())}")
            if (aboard == men.size) boarded = true
            if (boarded && near == men.size) {
                scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** A permanent driver keeps a MOVE order instead of repeatedly entering infantry dismount. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 180, batch = "vehicle-crew-contact")
    fun permanentDriverContinuesMoveUnderContact(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-crew-contact")
        val manager = SquadManager.get(helper.level)
        val lav = park(helper, TransportVehicle.LAV_150, Vec3(22.5, 1.0, 20.5))
        emptyVehicleCargo(lav)
        val driver = scene.spawn(Vec3(20.5, 1.0, 20.5)).also { it.isNoAi = true }
        driver.assignedVehicleId = lav.uuid
        helper.assertTrue(driver.startRiding(lav, true), "The permanent driver could not board")
        helper.assertTrue(!Ports.vehicles.seatHasAmmo(lav, driver), "This regression needs a driver whose gun cannot engage")
        val enemy = scene.enemy(Vec3(40.5, 1.0, 65.5)).also { it.isNoAi = true }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(driver.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, helper.absolutePos(BlockPos(22, 1, 200)))
        val (start, tick, stop) = listOf("start", "tick", "stop").map { name ->
            VehicleTransportBehaviour::class.java.getDeclaredMethod(name, NpcEntity::class.java).apply { isAccessible = true }
        }
        val trip = VehicleTransportBehaviour()
        start.invoke(trip, driver)
        scene.onClose = {
            stop.invoke(trip, driver)
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        helper.onEachTick {
            BrainUtils.setTargetOfEntity(driver, enemy)
            driver.sensing.tick()
            tick.invoke(trip, driver)
            if (helper.tick >= 120) {
                val here = helper.relativeVec(lav.position())
                helper.assertTrue(driver.vehicle === lav, "The permanent driver abandoned its vehicle")
                helper.assertTrue(here.z > 35, "Contact kept stopping the permanent crew before its MOVE objective: $here")
                scene.result("PASS", "tick=${helper.tick} position=$here")
                helper.succeed()
            }
        }
    }

    /** Arrival under ATTACK keeps the turret covered while the other riders get out. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "vehicle-arrival")
    fun attackArrivalKeepsGunnerUntilTenSecondsWithoutContact(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-arrival")
        val manager = SquadManager.get(helper.level)
        val lav = park(helper, TransportVehicle.LAV_25, Vec3(24.5, 1.0, 20.5))
        val men = boardArrivalCrew(helper, scene, lav)
        val gunner = men.first()
        val enemy = scene.enemy(Vec3(160.5, 1.0, 20.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, enemy.blockPosition())
        // Run only the transport executor: the scene checks the real seats and arrival transition,
        // without navigation or target selection changing the trip before it reaches the endpoint.
        val (start, tick, stop) = listOf("start", "tick", "stop").map { name ->
            VehicleTransportBehaviour::class.java.getDeclaredMethod(name, NpcEntity::class.java).apply { isAccessible = true }
        }
        val trips = men.map { VehicleTransportBehaviour().also { trip -> start.invoke(trip, it) } }
        scene.onClose = {
            trips.zip(men).forEach { (trip, man) -> stop.invoke(trip, man) }
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        var lostSightAt = 0
        helper.onEachTick {
            if (helper.tick == 2L) {
                val at = helper.absoluteVec(Vec3(145.5, 1.0, 20.5))
                lav.moveTo(at.x, at.y, at.z, 0f, 0f)
                overheatVehicleGun(lav, gunner)
                helper.assertTrue(Ports.vehicles.seatHasAmmo(lav, gunner) && !Ports.vehicles.canFire(lav, gunner), "The arriving gun must be cooling with ammunition left")
            }
            if (helper.tick < 60) men.forEach { BrainUtils.setTargetOfEntity(it, enemy) }
            if (helper.tick == 60L) {
                lostSightAt = gunner.tickCount
                enemy.discard()
                men.forEach { BrainUtils.setTargetOfEntity(it, null) }
            }
            lav.deltaMovement = Vec3.ZERO
            Ports.vehicles.cutPower(lav)
            men.forEach { it.sensing.tick() }
            trips.zip(men).forEach { (trip, man) -> if (man.vehicle === lav) tick.invoke(trip, man) }
            if (arrivalSeatsCleared(helper, lav, men, lostSightAt)) {
                scene.result("PASS", "tick=${helper.tick} lostSightAt=$lostSightAt dismountedAt=${gunner.tickCount}")
                helper.succeed()
            }
        }
    }

    /**
     * A rifleman who sees an enemy vehicle's crew takes the gun of a free vehicle beside him, and
     * leaves it once that enemy is gone.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "vehicle-support")
    fun riflemanTakesAFreeVehicleGunAgainstArmourAndLeavesItAfter(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-support")
        val manager = SquadManager.get(helper.level)
        val man = scene.spawn(Vec3(20.5, 1.0, 20.5))
        val gun = park(helper, TransportVehicle.LAV_150, Vec3(24.5, 1.0, 20.5))
        val enemyHull = park(helper, TransportVehicle.LAV_150, Vec3(24.5, 1.0, 56.5))
        val crew = scene.enemy(Vec3(24.5, 1.0, 58.5))
        helper.assertTrue(crew.startRiding(enemyHull, true), "The enemy crewman could not get in")
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
            enemyHull.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, man.blockPosition())
        var seatedAt: Long? = null
        var enemyGoneAt: Long? = null
        helper.onEachTick {
            if (helper.tick % 50L == 0L) scene.result("RUNNING", "tick=${helper.tick} seated=${man.vehicle === gun} enemy=${crew.isAlive}")
            if (seatedAt == null && man.vehicle === gun) {
                seatedAt = helper.tick
                crew.discard()
                enemyGoneAt = helper.tick
            }
            val gone = enemyGoneAt ?: return@onEachTick
            if (man.vehicle == null) {
                helper.assertTrue(helper.tick - gone >= 40, "He left the gun at once instead of watching for the next target")
                scene.result("PASS", "tick=${helper.tick} seatedAt=$seatedAt")
                helper.succeed()
            }
        }
    }
}
