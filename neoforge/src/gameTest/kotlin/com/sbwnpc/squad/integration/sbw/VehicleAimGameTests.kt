package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.VehicleModel
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTargetSensor
import com.sbwnpc.squad.entity.ai.VehicleCombatSupportBehaviour
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.gametest.CombatIdleGameTests
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

/** Real SBW weapon limits constrain the production combat-support executor. */
@PrefixGameTestTemplate(false)
object VehicleAimGameTests {
    private fun station(helper: GameTestHelper, scene: CombatIdleGameTests.Scene, model: VehicleModel, seat: Int = 0, height: Double = 80.0): Pair<VehicleEntity, NpcEntity> {
        val gun = requireNotNull(Ports.vehicles.create(helper.level, model)) as VehicleEntity
        val at = helper.absoluteVec(Vec3(24.5, height, 20.5))
        gun.moveTo(at.x, at.y, at.z, 0f, 0f)
        gun.setNoGravity(true)
        helper.level.addFreshEntity(gun)
        Ports.vehicles.fuelAndArm(gun, model)
        val man = scene.spawn(Vec3(24.5, height, 20.5)).also { it.isNoAi = true }
        helper.assertTrue(man.startRiding(gun, true), "The gunner could not board $model")
        if (Ports.vehicles.seatOf(gun, man) != seat) helper.assertTrue(Ports.vehicles.changeSeat(gun, man, seat), "The gunner could not take $model seat $seat")
        return gun to man
    }

    /** Main turrets and secondary stations accept their forward shot and reject vertical extremes. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 80, batch = "vehicle-aim-limits")
    fun vehicleStationsRespectNativeElevationAndDepression(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-aim-limits")
        val stations = listOf(TransportVehicle.LAV_25 to 0, TransportVehicle.BMP_2 to 6, TankModel.T_90A to 1).mapIndexed { index, (model, seat) ->
            station(helper, scene, model, seat).also { (gun, _) -> gun.setPos(gun.x + index * 32, gun.y, gun.z) }
        }
        val enemy = scene.enemy(Vec3(24.5, 80.0, 80.5)).also {
            it.isNoAi = true
            it.setNoGravity(true)
        }
        scene.onClose = { stations.forEach { it.first.discard() } }
        helper.runAtTickTime(2) {
            for ((gun, man) in stations) {
                val facing = gun.getTransformDirection(1f, man)
                man.yRot = -com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getYRotFromVector(facing).toFloat()
                man.yHeadRot = man.yRot
                man.xRot = -com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getXRotFromVector(facing).toFloat()
                val muzzle = gun.getShootPos(man, 1f)
                val forward = requireNotNull(gun.getShootVec(man, 1f))
                for ((offset, expected) in listOf(forward.scale(60.0) to true, Vec3(0.0, 60.0, 0.0) to false, Vec3(0.0, -50.0, 0.0) to false)) {
                    val at = muzzle.add(offset).add(0.0, -enemy.bbHeight / 2.0, 0.0)
                    enemy.moveTo(at.x, at.y, at.z, 0f, 0f)
                    helper.assertTrue(Ports.vehicles.canAimAt(gun, man, enemy) == expected, "${gun.type} seat ${Ports.vehicles.seatOf(gun, man)} offset=$offset expected reachable=$expected")
                }
            }
            scene.result("PASS", "stations=${stations.size} directions=3")
            helper.succeed()
        }
    }

    /** An unreachable ordered/retained contact cannot hide a reachable alternative from the gunner. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 160, batch = "vehicle-aim-selection")
    fun gunnerReplacesAnUnreachableContactWithAReachableTarget(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-aim-selection")
        val (gun, man) = station(helper, scene, TransportVehicle.LAV_25, height = 1.0)
        man.npcRank = NpcRank.ELITE
        val high = scene.enemy(Vec3(24.5, 12.0, 24.5)).also {
            it.isNoAi = true
            it.setNoGravity(true)
        }
        val reachable = scene.enemy(Vec3(24.5, 1.0, 58.5)).also { it.isNoAi = true }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, high.blockPosition())
        manager.setFocus(squad.id, high.uuid)
        BrainUtils.setTargetOfEntity(man, high)
        val sensor = SquadTargetSensor()
        val scan = SquadTargetSensor::class.java.getDeclaredMethod("doTick", ServerLevel::class.java, NpcEntity::class.java).apply { isAccessible = true }
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
        }
        helper.onEachTick {
            man.sensing.tick()
            scan.invoke(sensor, helper.level, man)
            helper.assertTrue(man.target !== high, "The gunner retained a target above the turret's elevation")
            if (man.target === reachable) {
                scene.result("PASS", "tick=${helper.tick} target=${reachable.uuid}")
                helper.succeed()
            }
        }
    }

    /** Eye-to-body sight cannot authorise a shot whose real muzzle is behind cover. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 80, batch = "vehicle-muzzle-cover")
    fun vehicleGunRejectsCoverAtItsMuzzleDespiteClearEyes(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-muzzle-cover")
        val (gun, man) = station(helper, scene, TransportVehicle.LAV_25, height = 1.0)
        val enemy = scene.enemy(Vec3(24.5, 1.0, 80.5)).also { it.isNoAi = true }
        var obstruction: BlockPos? = null
        scene.onClose = {
            obstruction?.let { helper.level.setBlockAndUpdate(it, Blocks.AIR.defaultBlockState()) }
            gun.discard()
        }
        helper.runAtTickTime(2) {
            helper.assertTrue(Ports.vehicles.canAimAt(gun, man, enemy), "The unobstructed control shot must be reachable")
            val muzzle = gun.getShootPos(man, 1f)
            val aim = enemy.boundingBox.center
            for (step in 1..16) {
                val block = BlockPos.containing(muzzle.lerp(aim, step / 20.0))
                if (!helper.level.getBlockState(block).isAir) continue
                helper.level.setBlockAndUpdate(block, Blocks.BEDROCK.defaultBlockState())
                if (DetectionSightline.visible(helper.level, man.eyePosition, aim, man) && Sightline.blocked(helper.level, muzzle, aim, man)) {
                    obstruction = block
                    break
                }
                helper.level.setBlockAndUpdate(block, Blocks.AIR.defaultBlockState())
            }
            helper.assertTrue(obstruction != null, "The fixture did not separate eye and muzzle sightlines: eyes=${man.eyePosition} muzzle=$muzzle")
            helper.assertTrue(!Ports.vehicles.canAimAt(gun, man, enemy), "A clear eye line kept a blocked muzzle target")
            scene.result("PASS", "obstruction=$obstruction")
            helper.succeed()
        }
    }

    /** A visible target above the gun's native elevation must not make a man claim that gun. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 200, batch = "vehicle-elevation")
    fun freeGunRejectsTargetAboveItsNativeElevation(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-elevation")
        val man = scene.spawn(Vec3(22.5, 1.0, 20.5)).also { it.isNoAi = true }
        val enemy = scene.enemy(Vec3(24.5, 100.0, 20.5)).also {
            it.isNoAi = true
            it.setNoGravity(true)
        }
        val gun = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25)) as VehicleEntity
        val at = helper.absoluteVec(Vec3(24.5, 1.0, 20.5))
        gun.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(gun)
        Ports.vehicles.fuelAndArm(gun, TransportVehicle.LAV_25)
        helper.assertTrue(gun.turretMaxPitch < 85, "The fixture needs a gun unable to point vertically")
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, man.blockPosition())
        val behaviour = VehicleCombatSupportBehaviour()
        val check = VehicleCombatSupportBehaviour::class.java.getDeclaredMethod("checkExtraStartConditions", ServerLevel::class.java, NpcEntity::class.java).apply { isAccessible = true }
        val (start, tick, stop) = listOf("start", "tick", "stop").map { name ->
            VehicleCombatSupportBehaviour::class.java.getDeclaredMethod(name, NpcEntity::class.java).apply { isAccessible = true }
        }
        var started = false
        scene.onClose = {
            if (started) stop.invoke(behaviour, man)
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
        }
        helper.onEachTick {
            if (helper.tick == 40L) {
                val reachable = helper.absoluteVec(Vec3(24.5, 1.0, 58.5))
                enemy.moveTo(reachable.x, reachable.y, reachable.z, 0f, 0f)
            }
            BrainUtils.setTargetOfEntity(man, enemy)
            man.sensing.tick()
            if (!started && check.invoke(behaviour, helper.level, man) == true) {
                start.invoke(behaviour, man)
                started = true
            }
            if (started) tick.invoke(behaviour, man)
            if (helper.tick < 40) {
                helper.assertTrue(VehicleTransportClaims.vehicleOf(man.uuid) == null, "The man claimed a gun that cannot elevate to its visible target")
            } else if (man.vehicle === gun) {
                scene.result("PASS", "tick=${helper.tick} nativeElevation=${gun.turretMaxPitch}")
                helper.succeed()
            }
        }
    }

    /**
     * A target that ducks behind cover stays the gunner's: its gun can still be laid that way. In-game
     * 24.10 the muzzle-sightline check dropped every enemy the moment it was hidden, and any the squad
     * passed on, so turrets fired only at what they saw themselves.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "vehicle-aim-hidden")
    fun gunnerKeepsATargetThatDucksBehindCover(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-aim-hidden")
        val (gun, man) = station(helper, scene, TransportVehicle.LAV_25, height = 1.0)
        val enemy = scene.enemy(Vec3(24.5, 1.0, 60.5)).also { it.isNoAi = true }
        for (x in 12..36) for (y in 1..8) helper.setBlock(BlockPos(x, y, 40), Blocks.BEDROCK)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, enemy.blockPosition())
        manager.setFocus(squad.id, enemy.uuid)
        BrainUtils.setTargetOfEntity(man, enemy)
        val sensor = SquadTargetSensor()
        val scan = SquadTargetSensor::class.java.getDeclaredMethod("doTick", ServerLevel::class.java, NpcEntity::class.java).apply { isAccessible = true }
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
        }
        helper.onEachTick {
            man.sensing.tick()
            scan.invoke(sensor, helper.level, man)
            helper.assertTrue(!DetectionSightline.canSee(man, enemy), "The wall did not hide the enemy")
            helper.assertTrue(man.target === enemy, "The gunner dropped a hidden target at tick ${helper.tick}: ${man.target}")
            if (helper.tick >= 60L) {
                scene.result("PASS", "kept=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /**
     * A gunner whose squad's focus is hidden turns to an enemy he can shoot. In-game 24.10 the gun
     * stayed laid on a man behind cover, as the squad's focus it could still reach, while another
     * stood in the open, and the turret sat silent until the gunner gave up and climbed out.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 200, batch = "vehicle-aim-visible")
    fun gunnerTurnsFromAHiddenFocusToAnEnemyInTheOpen(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "vehicle-aim-visible")
        val (gun, man) = station(helper, scene, TransportVehicle.LAV_25, height = 1.0)
        val hidden = scene.enemy(Vec3(24.5, 1.0, 60.5)).also { it.isNoAi = true }
        val open = scene.enemy(Vec3(48.5, 1.0, 24.5)).also { it.isNoAi = true }
        for (x in 12..36) for (y in 1..8) helper.setBlock(BlockPos(x, y, 40), Blocks.BEDROCK)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, hidden.blockPosition())
        manager.setFocus(squad.id, hidden.uuid)
        BrainUtils.setTargetOfEntity(man, hidden)
        val sensor = SquadTargetSensor()
        val scan = SquadTargetSensor::class.java.getDeclaredMethod("doTick", ServerLevel::class.java, NpcEntity::class.java).apply { isAccessible = true }
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
        }
        helper.onEachTick {
            man.sensing.tick()
            scan.invoke(sensor, helper.level, man)
            helper.assertTrue(DetectionSightline.canSee(man, open) && !DetectionSightline.canSee(man, hidden), "The scene's sightlines are wrong")
            if (man.target === open) {
                scene.result("PASS", "turned=${helper.tick}")
                helper.succeed()
            }
            if (helper.tick >= 180L) helper.fail("The gunner stayed on the hidden focus: ${man.target}")
        }
    }
}
