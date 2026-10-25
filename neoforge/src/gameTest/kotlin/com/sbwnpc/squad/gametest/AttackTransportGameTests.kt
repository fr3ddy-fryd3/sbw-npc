package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
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
import java.util.UUID

/**
 * An ATTACK's ride ends at the first contact, and the driver stays at the vehicle's gun after it
 * (#200, playtest 25.10).
 */
@PrefixGameTestTemplate(false)
object AttackTransportGameTests {
    private val OBJECTIVE = BlockPos(88, 1, 210)

    private class Ride(val scene: CombatIdleGameTests.Scene, val men: List<NpcEntity>, val lav: Entity, val squad: Squad) {
        val driver: NpcEntity? get() = Ports.vehicles.seating(lav).firstOrNull() as? NpcEntity
        val riders: List<NpcEntity> get() = men.filter { it !== driver }
        fun distanceToObjective(helper: GameTestHelper): Double {
            val at = helper.relativeVec(lav.position())
            return Math.hypot(at.x - OBJECTIVE.x, at.z - OBJECTIVE.z)
        }
    }

    private fun ride(helper: GameTestHelper, name: String, size: Int = 3): Ride {
        val scene = CombatIdleGameTests.Scene(helper, name)
        DebugFlags.enabledGroups += LogGroup.VEHICLE
        val manager = SquadManager.get(helper.level)
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(88.5, 1.0, 30.5))
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        Ports.vehicles.fuelAndArm(lav, TransportVehicle.LAV_25)
        val men = (0 until size).map { scene.spawn(Vec3(84.5 + it * 2, 1.0, 22.5)) }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(OBJECTIVE))
        return Ride(scene, men, lav, squad)
    }

    /** The ride ends where the crew first sees the defenders, not on their point. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "attack-transport-contact")
    fun attackRideUnloadsWhereTheCrewSeesTheEnemy(helper: GameTestHelper) {
        val ride = ride(helper, "attack-transport-contact")
        val defender = ride.scene.enemy(Vec3(OBJECTIVE.x + 0.5, 1.0, OBJECTIVE.z - 4.5))
        var boarded = false
        var unloadedAt: Long? = null
        helper.onEachTick {
            if (ride.men.all { it.vehicle === ride.lav }) boarded = true
            if (!boarded) return@onEachTick
            if (unloadedAt == null && ride.riders.any { it.vehicle == null }) {
                val distance = ride.lav.distanceTo(defender)
                helper.assertTrue(distance >= 80.0, "The riders got out only $distance blocks from the defender")
                unloadedAt = helper.tick
            }
            val since = unloadedAt?.let { helper.tick - it } ?: return@onEachTick
            if (since >= 60) {
                helper.assertTrue(ride.riders.all { it.vehicle == null }, "Not every rider got out at the contact")
                ride.scene.result("PASS", "unloaded at tick=$unloadedAt, ${ride.lav.distanceTo(defender)} blocks from the defender")
                helper.succeed()
            }
        }
    }

    /** The driver holds the gun seat after the unload instead of leaving the vehicle empty. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "attack-transport-overwatch")
    fun attackDriverStaysAtTheGunAfterTheUnload(helper: GameTestHelper) {
        val ride = ride(helper, "attack-transport-overwatch")
        var boarded = false
        var unloadedAt: Long? = null
        helper.onEachTick {
            if (ride.men.all { it.vehicle === ride.lav }) boarded = true
            if (!boarded) return@onEachTick
            if (unloadedAt == null && ride.riders.all { it.vehicle == null }) unloadedAt = helper.tick
            val since = unloadedAt?.let { helper.tick - it } ?: return@onEachTick
            val driver = ride.driver
            helper.assertTrue(
                driver != null && Ports.vehicles.seating(ride.lav).firstOrNull() === driver,
                "The driver left the gun seat $since ticks after the unload",
            )
            if (since >= 300) {
                helper.assertTrue(ride.lav.deltaMovement.horizontalDistance() < 0.05, "The vehicle kept driving after the unload")
                ride.scene.result("PASS", "driver seated for $since ticks after the unload")
                helper.succeed()
            }
        }
    }

    /** Runs [check] each tick once the driver has held the gun for 100 ticks after the unload, after [reorder]. */
    private fun afterOverwatch(helper: GameTestHelper, name: String, reorder: (Ride) -> Unit, check: (Ride, Long) -> Unit) {
        val ride = ride(helper, name)
        var boarded = false
        var unloadedAt: Long? = null
        var reorderedAt: Long? = null
        helper.onEachTick {
            if (ride.men.all { it.vehicle === ride.lav }) boarded = true
            if (!boarded) return@onEachTick
            if (unloadedAt == null && ride.riders.all { it.vehicle == null }) unloadedAt = helper.tick
            val unloaded = unloadedAt ?: return@onEachTick
            if (reorderedAt == null && helper.tick - unloaded >= 100) {
                helper.assertTrue(ride.driver?.vehicleOverwatch == true, "The driver was not at the gun before the new order")
                reorder(ride)
                reorderedAt = helper.tick
            }
            reorderedAt?.let { check(ride, helper.tick - it) }
        }
    }

    /** A new far order is driven from the gun seat, with the squad picked up on the way out. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 2000, batch = "attack-transport-far-order")
    fun attackDriverDrivesANewFarOrderFromTheGun(helper: GameTestHelper) {
        val manager = SquadManager.get(helper.level)
        var parked: Vec3? = null
        afterOverwatch(helper, "attack-transport-far-order", { ride ->
            parked = ride.lav.position()
            manager.issueOrderAt(helper.level, ride.squad.id, SquadOrder.MOVE, helper.absolutePos(BlockPos(88, 1, 8)))
        }) { ride, since ->
            val driver = ride.driver
            helper.assertTrue(driver != null && driver.vehicle === ride.lav, "The driver got out for a far order")
            if (ride.lav.position().distanceTo(parked!!) > 12.0) {
                helper.assertTrue(!driver!!.vehicleOverwatch, "The driver was still flagged at the gun under the new order")
                ride.scene.result("PASS", "driving $since ticks after the new order, aboard=${ride.men.count { it.vehicle === ride.lav }}")
                helper.succeed()
            }
        }
    }

    /** A new near order that is not an attack takes the driver off the gun, and he walks it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "attack-transport-near-order")
    fun attackDriverGetsOutForANewNearOrder(helper: GameTestHelper) {
        val manager = SquadManager.get(helper.level)
        var driver: NpcEntity? = null
        afterOverwatch(helper, "attack-transport-near-order", { ride ->
            driver = ride.driver
            manager.issueOrderAt(helper.level, ride.squad.id, SquadOrder.DEFEND, BlockPos.containing(ride.lav.position()).offset(0, 0, 20))
        }) { ride, since ->
            val man = driver!!
            if (man.vehicle == null) {
                helper.assertTrue(!man.vehicleOverwatch, "The driver got out still flagged at the gun")
                ride.scene.result("PASS", "out $since ticks after the new order")
                helper.succeed()
            }
            helper.assertTrue(since < 200, "The driver stayed at the gun under a near DEFEND")
        }
    }
}
