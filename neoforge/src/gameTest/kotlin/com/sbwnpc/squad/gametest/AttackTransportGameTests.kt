package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTransit
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

/**
 * An ATTACK's ride stops [SquadTransit.TRANSPORT_DISTANCE] short of the objective, and the driver
 * works the vehicle's gun on the way and after the unload (#200, playtest 25.10).
 */
@PrefixGameTestTemplate(false)
object AttackTransportGameTests {
    private val OBJECTIVE = BlockPos(88, 1, 210)

    private class Ride(val scene: CombatIdleGameTests.Scene, val men: List<NpcEntity>, val lav: Entity) {
        val driver: NpcEntity? get() = VehicleTransportClaims.driverOf(lav.uuid)?.let { id -> men.firstOrNull { it.uuid == id } }
        val riders: List<NpcEntity> get() = men.filter { it !== driver }
        fun distanceToObjective(helper: GameTestHelper): Double {
            val at = helper.relativeVec(lav.position())
            return Math.hypot(at.x - OBJECTIVE.x, at.z - OBJECTIVE.z)
        }
    }

    private fun ride(helper: GameTestHelper, name: String, size: Int = 3, crew: Boolean = false): Ride {
        val scene = CombatIdleGameTests.Scene(helper, name)
        DebugFlags.enabledGroups += LogGroup.VEHICLE
        val manager = SquadManager.get(helper.level)
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(88.5, 1.0, 30.5))
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        Ports.vehicles.fuelAndArm(lav, TransportVehicle.LAV_25)
        val men = (0 until size).map { scene.spawn(Vec3(84.5 + it * 2, 1.0, 22.5)) }
        if (crew) {
            men.single().assignedVehicleId = lav.uuid
            helper.assertTrue(men.single().startRiding(lav, true), "The crewman could not board the LAV")
        }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(OBJECTIVE))
        return Ride(scene, men, lav)
    }

    /** The riders get out about 100 blocks from the point, not on it, and walk on from there. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "attack-transport-standoff")
    fun attackTransportUnloadsShortOfTheObjective(helper: GameTestHelper) {
        val ride = ride(helper, "attack-transport-standoff")
        var boarded = false
        var unloadedAt: Long? = null
        helper.onEachTick {
            if (ride.men.all { it.vehicle === ride.lav }) boarded = true
            if (!boarded) return@onEachTick
            val out = ride.riders.filter { it.vehicle == null }
            if (unloadedAt == null && out.isNotEmpty()) {
                val distance = ride.distanceToObjective(helper)
                helper.assertTrue(
                    distance >= SquadTransit.TRANSPORT_DISTANCE - 15 && distance <= SquadTransit.TRANSPORT_DISTANCE + 5,
                    "The riders got out $distance blocks from the objective",
                )
                unloadedAt = helper.tick
            }
            val since = unloadedAt?.let { helper.tick - it } ?: return@onEachTick
            helper.assertTrue(ride.riders.none { it.vehicle === ride.lav }, "A rider climbed back into the vehicle after the unload")
            if (since >= 200) {
                helper.assertTrue(ride.riders.all { it.distanceTo(ride.lav) > 8.0 }, "The riders did not walk on from the unload point")
                ride.scene.result("PASS", "unloaded at tick=$unloadedAt distance=${ride.distanceToObjective(helper)}")
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

    /** An enemy beside the way is shot at while the vehicle is still moving. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "attack-transport-fire")
    fun attackDriverFiresTheGunOnTheMove(helper: GameTestHelper) {
        val ride = ride(helper, "attack-transport-fire")
        var enemy: NpcEntity? = null
        val start = ride.lav.position()
        helper.onEachTick {
            // Spawned once the ride is under way: seen before boarding, it would keep the men off the ride.
            if (enemy == null && ride.lav.position().distanceTo(start) > 6.0) enemy = ride.scene.enemy(Vec3(98.5, 1.0, 90.5))
            val target = enemy ?: return@onEachTick
            if (target.health < target.maxHealth) {
                val speed = ride.lav.deltaMovement.horizontalDistance()
                val shooter = target.lastDamageSource?.entity
                helper.assertTrue(ride.riders.any { it.vehicle === ride.lav }, "The enemy was hit only after the unload")
                helper.assertTrue(shooter != null && shooter === ride.driver, "The enemy was hit by $shooter, not by the driver at the gun")
                ride.scene.result("PASS", "hit at tick=${helper.tick} speed=$speed")
                helper.succeed()
            }
        }
    }

    /** A hit from far off is driven through; a hit from close by stops the ride and unloads it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "attack-transport-hit")
    fun attackRideStopsOnlyForFireFromCloseBy(helper: GameTestHelper) {
        val ride = ride(helper, "attack-transport-hit")
        var farHitAt: Long? = null
        var nearHitAt: Long? = null
        helper.onEachTick {
            val z = helper.relativeVec(ride.lav.position()).z
            if (farHitAt == null && z > 40.0) {
                val far = ride.scene.enemy(Vec3(88.5, 1.0, 215.5))
                ride.lav.hurt(helper.level.damageSources().mobAttack(far), 1f)
                farHitAt = helper.tick
            }
            val far = farHitAt ?: return@onEachTick
            if (nearHitAt == null && helper.tick - far >= 30) {
                helper.assertTrue(ride.riders.all { it.vehicle === ride.lav }, "A hit from more than 100 blocks off unloaded the ride")
                val near = ride.scene.enemy(helper.relativeVec(ride.lav.position()).add(16.0, 0.0, 16.0))
                ride.lav.hurt(helper.level.damageSources().mobAttack(near), 1f)
                nearHitAt = helper.tick
            }
            val near = nearHitAt ?: return@onEachTick
            if (ride.riders.all { it.vehicle == null }) {
                helper.assertTrue(
                    ride.distanceToObjective(helper) > SquadTransit.TRANSPORT_DISTANCE + 5,
                    "The ride went on to the standoff instead of stopping for the close hit",
                )
                ride.scene.result("PASS", "unloaded ${helper.tick - near} ticks after the close hit, ${ride.distanceToObjective(helper)} blocks out")
                helper.succeed()
            }
        }
    }

    /** A tank-style permanent crew still drives to its objective; the standoff is for carried squads. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "attack-transport-crew")
    fun permanentCrewDrivesOnToItsAttackObjective(helper: GameTestHelper) {
        val ride = ride(helper, "attack-transport-crew", size = 1, crew = true)
        helper.onEachTick {
            if (ride.distanceToObjective(helper) <= 25.0) {
                helper.assertTrue(ride.men.single().vehicle === ride.lav, "The crewman left his vehicle")
                ride.scene.result("PASS", "tick=${helper.tick} distance=${ride.distanceToObjective(helper)}")
                helper.succeed()
            }
        }
    }
}
