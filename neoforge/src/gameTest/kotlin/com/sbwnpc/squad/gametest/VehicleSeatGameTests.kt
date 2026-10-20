package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.VehicleModel
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
