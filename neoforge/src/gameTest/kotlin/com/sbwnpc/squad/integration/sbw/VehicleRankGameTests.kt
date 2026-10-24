package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.api.event.ShootEvent
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.VehicleModel
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTargetSensor
import com.sbwnpc.squad.gametest.CombatIdleGameTests
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.abs

/** Rank affects the real SBW shot path and acquisition of an ordered tank target. */
@PrefixGameTestTemplate(false)
object VehicleRankGameTests {
    /** Captures the parameters emitted after GunData's existing mixin has adjusted a real shot. */
    private class Shots(val helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-rank-spread")
        private val vehicles = mutableListOf<VehicleEntity>()
        var checked = 0
            private set

        init {
            scene.onClose = { vehicles.forEach { it.discard() } }
        }

        /** Fires a loaded weapon through the transformed SBW method and checks the emitted spread. */
        fun check(model: VehicleModel, seat: Int, rank: NpcRank) {
            val vehicle = requireNotNull(Ports.vehicles.create(helper.level, model)) as VehicleEntity
            vehicles += vehicle
            val at = helper.absoluteVec(Vec3(24.5, 1.0, 20.5 + checked * 10))
            vehicle.moveTo(at.x, at.y, at.z, 0f, 0f)
            helper.level.addFreshEntity(vehicle)
            Ports.vehicles.fuelAndArm(vehicle, model)
            val shooter = scene.spawn(Vec3(20.5, 1.0, 20.5 + checked * 10)).also {
                it.isNoAi = true
                it.npcRank = rank
            }
            helper.assertTrue(shooter.startRiding(vehicle, true), "Could not board $model")
            if (Ports.vehicles.seatOf(vehicle, shooter) != seat) helper.assertTrue(Ports.vehicles.changeSeat(vehicle, shooter, seat), "Could not take $model seat $seat")
            val data = requireNotNull(vehicle.getGunData(shooter))
            data.ammo.set(64)
            val savedSpread = data.get(GunProp.SPREAD)
            var observed: Double? = null
            val listener = Consumer<ShootEvent.Post> { if (it.shooter === shooter) observed = it.spread }
            NeoForge.EVENT_BUS.addListener(listener)
            try {
                data.shoot(ShootParameters(vehicle, shooter, helper.level, vehicle.getShootPos(shooter, 1f), Vec3(0.0, 1.0, 0.0), data, 0.5, false, null, null))
                helper.assertTrue(observed != null, "$model seat $seat produced no shot")
                val expected = if (rank == NpcRank.RECRUIT) 2.0 else 0.75
                helper.assertTrue(abs(requireNotNull(observed) - expected) < 1e-9, "$model seat $seat $rank spread=$observed expected=$expected")
                helper.assertTrue(data.get(GunProp.SPREAD) == savedSpread, "Rank changed the vehicle's stored weapon spread")
                checked++
            } finally {
                NeoForge.EVENT_BUS.unregister(listener)
            }
        }
    }

    /** Tank main guns, secondary stations and existing APC/helicopter turrets all use rank scatter. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 200, batch = "vehicle-rank-spread")
    fun allCrewedVehicleWeaponsApplyRankToRealShots(helper: GameTestHelper) {
        val shots = Shots(helper)
        val seats = listOf(
            TankModel.T_90A to 0,
            TankModel.T_90A to 1,
            TankModel.ZTZ_99A to 0,
            TankModel.M1A2 to 0,
            TransportVehicle.LAV_25 to 0,
            TransportVehicle.BMP_2 to 6,
            HelicopterModel.MI_28 to 1,
        )
        for ((model, seat) in seats) for (rank in listOf(NpcRank.RECRUIT, NpcRank.ELITE)) shots.check(model, seat, rank)
        shots.scene.result("PASS", "shots=${shots.checked} weaponSeats=${seats.size}")
        helper.succeed()
    }

    /** A commanded contact cannot give an inexperienced tank gunner instant acquisition. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 200, batch = "tank-rank-noticing")
    fun eliteTankGunnerAcquiresOrderedTargetBeforeRecruit(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "tank-rank-noticing")
        val manager = SquadManager.get(helper.level)
        val enemy = scene.enemy(Vec3(75.5, 1.0, 80.5))
        val vehicles = listOf(70.5, 80.5).map { x ->
            (requireNotNull(Ports.vehicles.create(helper.level, TankModel.T_90A)) as VehicleEntity).also {
                val at = helper.absoluteVec(Vec3(x, 1.0, 20.5))
                it.moveTo(at.x, at.y, at.z, 0f, 0f)
                helper.level.addFreshEntity(it)
            }
        }
        val gunners = listOf(NpcRank.ELITE, NpcRank.RECRUIT).mapIndexed { index, rank ->
            scene.spawn(Vec3(70.5 + index * 10, 1.0, 17.5)).also {
                it.isNoAi = true
                it.npcRank = rank
                helper.assertTrue(it.startRiding(vehicles[index], true), "The $rank could not board")
            }
        }
        val squads = gunners.map { manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(it.uuid)) }
        squads.forEach {
            manager.issueOrderAt(helper.level, it.id, SquadOrder.ATTACK, enemy.blockPosition())
            manager.setFocus(it.id, enemy.uuid)
        }
        scene.onClose = {
            squads.forEach { manager.deleteSquad(helper.level, it.id) }
            vehicles.forEach { it.discard() }
        }
        // Scan on the same ticks: scheduler jitter must not masquerade as a rank advantage.
        val sensors = List(2) { SquadTargetSensor() }
        val scan = SquadTargetSensor::class.java.getDeclaredMethod("doTick", ServerLevel::class.java, NpcEntity::class.java).apply { isAccessible = true }
        val acquired = arrayOfNulls<Long>(2)
        helper.onEachTick {
            gunners.forEachIndexed { index, man ->
                man.sensing.tick()
                scan.invoke(sensors[index], helper.level, man)
                if (acquired[index] == null && man.target === enemy) acquired[index] = helper.tick
            }
            val elite = acquired[0]
            val recruit = acquired[1]
            if (elite != null && recruit != null) {
                helper.assertTrue(recruit > elite, "The recruit acquired at $recruit, no later than the elite at $elite")
                scene.result("PASS", "elite=$elite recruit=$recruit")
                helper.succeed()
            }
        }
    }
}
