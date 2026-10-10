package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.BenchAttackOrbit
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.SquadDeployment
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

@PrefixGameTestTemplate(false)
object HelicopterOrbitGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 40, batch = "helicopter-deployment")
    fun bothHelicopterPresetsDeployWithPilotAndFaction(helper: GameTestHelper) {
        val manager = SquadManager.get(helper.level)
        val vehicles = mutableListOf<Entity>()
        val deployments = HelicopterModel.entries.mapIndexed { index, model ->
            val config = SquadToolItem.Config(NpcClass.RIFLEMAN, NpcRank.RECRUIT, SquadFaction.PIG, SquadPreset.HELI_CREW, heliModel = model)
            val result = SquadDeployment.deploy(helper.level, helper.absolutePos(BlockPos(40 + index * 60, 1, 80)), 0f, config, UUID.randomUUID())
                ?: throw AssertionError("Failed to deploy $model")
            helper.assertTrue(!result.vehicleBlocked, "No aircraft deployed for $model")
            helper.assertTrue(result.members.map { it.npcClass } == model.crew, "Incorrect crew for $model")
            val pilot = result.members.first { it.npcClass == NpcClass.HELICOPTER_PILOT }
            val aircraft = pilot.vehicle ?: throw AssertionError("Pilot did not board $model")
            vehicles += aircraft
            helper.assertTrue(Ports.vehicles.modelOf(aircraft) == model, "Wrong helicopter model")
            helper.assertTrue(aircraft.firstPassenger === pilot, "Pilot did not take the controls")
            helper.assertTrue(SquadTeams.factionOf(aircraft) == SquadFaction.PIG, "Aircraft lost its faction")
            helper.assertTrue(Helicopters.canFly(aircraft), "Aircraft is not fuelled and healthy")
            result
        }
        helper.runAtTickTime(5) {
            helper.assertTrue(vehicles.all { it.isAlive && it.firstPassenger is NpcEntity }, "Aircraft or pilot lost during first ticks")
            deployments.forEach { result ->
                result.squad?.let { manager.disband(helper.level, it.id) }
                result.members.forEach { it.discard() }
            }
            vehicles.forEach { it.discard() }
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 100, batch = "mounted-firing-safety")
    fun mountedBulletSafetyStillProtectsBlastAndDismountedAllies(helper: GameTestHelper) {
        val heli = Ports.vehicles.create(helper.level, HelicopterModel.AH_6)!!
        val pad = helper.absoluteVec(Vec3(20.5, 1.0, 20.5))
        heli.moveTo(pad.x, pad.y, pad.z, 0f, 0f)
        helper.level.addFreshEntity(heli)
        val crew = (0..3).map { index ->
            AttackDeploymentGameTests.spawn(helper, Vec3(20.5 + index, 1.0, 22.5), SquadFaction.PIG).apply {
                isNoAi = true
                helper.assertTrue(startRiding(heli), "Safety fixture did not seat the crew")
            }
        }
        helper.runAtTickTime(2) {
            val shooter = crew[2]
            val ally = crew[3]
            val direction = ally.eyePosition.subtract(shooter.eyePosition).normalize()
            val aim = shooter.eyePosition.add(direction.scale(30.0))
            val mounted = FriendlyFireGuard.assess(shooter, aim, 0.0, ally.position(), 5.0)
            helper.assertTrue(mounted.lineClear, "Own passengers wrongly block rounds that cannot hit them")
            helper.assertTrue(!mounted.blastClear, "Sharing a vehicle must not exempt allies from explosive safety")
            ally.stopRiding()
            val blocker = shooter.eyePosition.add(direction.scale(4.0)).subtract(0.0, ally.eyeHeight.toDouble(), 0.0)
            ally.moveTo(blocker.x, blocker.y, blocker.z)
            helper.assertTrue(!FriendlyFireGuard.hasClearLineOfFire(shooter, aim), "Dismounted ally in the firing lane must still block fire")
            crew.forEach { it.discard() }
            heli.discard()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 1800, batch = "transport-bench-orbit")
    fun transportOrbitsEnemyAndUsesSurvivingBench(helper: GameTestHelper) {
        val scene = Scene(helper)
        var entered = false
        var previousAngle: Double? = null
        var swept = 0.0
        var switched = false
        var afterSwitch = 0L
        var positiveRounds = 0
        var negativeRounds = 0
        var left: NpcEntity? = null
        var right: NpcEntity? = null
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            if (event.level !== helper.level || !Ports.guns.isRound(round)) return@Consumer
            if (round.owner === left) positiveRounds++
            if (round.owner === right) negativeRounds++
        }
        NeoForge.EVENT_BUS.addListener(listener)
        scene.onClose = { NeoForge.EVENT_BUS.unregister(listener) }
        helper.onEachTick {
            scene.tick()
            val enemy = scene.enemy ?: return@onEachTick
            if (left == null) {
                left = Ports.vehicles.seating(scene.heli).getOrNull(2) as? NpcEntity
                right = Ports.vehicles.seating(scene.heli).getOrNull(3) as? NpcEntity
            }
            val relative = scene.heli.position().subtract(enemy.position())
            val radius = relative.horizontalDistance()
            if (scene.airborne && radius <= 55.0) entered = true
            if (!entered) return@onEachTick
            helper.assertTrue(radius > 24.0, "Transport overflew the enemy instead of circling: radius=$radius")
            val angle = atan2(relative.z, relative.x)
            previousAngle?.let { previous -> swept += wrapped(angle - previous) }
            previousAngle = angle
            if (!switched && swept >= PI / 2 && positiveRounds >= 5) {
                helper.assertTrue(left != null && right != null, "Natural boarding did not fill both firing benches")
                // SBW forwards 70% of ordinary passenger damage to the airframe: /kill's
                // Float.MAX_VALUE destroys both. Its explosion damage tag does not transfer
                // damage, so this models a fatal bench casualty on an intact aircraft.
                left!!.hurt(helper.level.damageSources().explosion(scene.enemy, scene.enemy), 1000f)
                helper.assertTrue(!left!!.isAlive, "The casualty fixture did not kill the bench gunner")
                switched = true
                afterSwitch = helper.tick
                swept = 0.0
                negativeRounds = 0
            }
            if (switched && helper.tick - afterSwitch >= 150 && swept <= -PI / 2 && negativeRounds >= 5) {
                scene.result("positiveRounds=$positiveRounds negativeRounds=$negativeRounds oppositeSweep=$swept switchedAt=$afterSwitch")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 2400, batch = "transport-moving-retreat")
    fun transportTracksMovingContactThenRetreatsAndLands(helper: GameTestHelper) {
        val scene = Scene(helper)
        for (x in 78..85) for (z in 90..110) for (y in 1..12) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        var movingSince: Long? = null
        var enemyOrigin: Vec3? = null
        var retreatAt: Long? = null
        var landed = false
        var swept = 0.0
        var previousAngle: Double? = null
        helper.onEachTick {
            scene.tick()
            val enemy = scene.enemy ?: return@onEachTick
            val delta = scene.heli.position().subtract(enemy.position())
            if (retreatAt == null && scene.airborne && delta.horizontalDistance() <= 55.0 && movingSince == null) {
                movingSince = helper.tick
                enemyOrigin = enemy.position()
            }
            if (movingSince != null && retreatAt == null) {
                // NoAI skips travel and clears velocity. Move the scripted target through vanilla
                // collision resolution, without teleporting it or steering the tested aircraft.
                enemy.move(MoverType.SELF, Vec3(0.06, 0.0, 0.0))
                val angle = atan2(delta.z, delta.x)
                previousAngle?.let { swept += wrapped(angle - it) }
                previousAngle = angle
                helper.assertTrue(delta.horizontalDistance() > 24.0, "Moving enemy was overflown")
                if (enemy.position().distanceTo(enemyOrigin!!) >= 16.0 && abs(swept) >= PI / 2) {
                    retreatAt = helper.tick
                    scene.manager.setOrder(scene.squad.id, SquadOrder.RETREAT)
                    scene.manager.setObjective(helper.level, scene.squad.id, helper.absolutePos(BlockPos(28, 1, 100)))
                }
            }
            if (retreatAt != null) {
                if (Helicopters.isGrounded(helper.level, scene.heli)) landed = true
                helper.assertTrue(landed || scene.squad.order == SquadOrder.RETREAT, "Arrival changed RETREAT to ${scene.squad.order} while the transport was still airborne")
            }
            if (retreatAt != null && Helicopters.isGrounded(helper.level, scene.heli) && scene.heli.position().subtract(scene.pad).horizontalDistance() < 10.0 && scene.crew.drop(1).all { !it.isPassenger }) {
                scene.result("movingSince=$movingSince enemyTravel=${enemy.position().distanceTo(enemyOrigin!!)} sweep=$swept retreat=$retreatAt landing=${helper.tick}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 1800, batch = "gunship-retreat-arrival")
    fun gunshipRetreatCompletesAfterPhysicalLanding(helper: GameTestHelper) {
        val scene = Scene(helper, HelicopterModel.MI_28, attack = false)
        val destination = helper.absoluteVec(Vec3(140.5, 1.0, 100.5))
        var launched = false
        var landed = false
        helper.onEachTick {
            scene.tick()
            if (scene.airborne) launched = true
            if (!launched) return@onEachTick
            if (Helicopters.isGrounded(helper.level, scene.heli)) landed = true
            helper.assertTrue(landed || scene.squad.order == SquadOrder.RETREAT, "Gunship completed retreat in the air")
            if (landed && scene.squad.order == SquadOrder.DEFEND && scene.heli.position().subtract(destination).horizontalDistance() < 10.0) {
                helper.assertTrue(scene.crew.all { it.vehicle === scene.heli }, "Permanent gunship crew abandoned its aircraft")
                scene.result("landing=${helper.tick} destinationDistance=${scene.heli.position().subtract(destination).horizontalDistance()}")
                helper.succeed()
            }
        }
    }

    private class Scene(val helper: GameTestHelper, model: HelicopterModel = HelicopterModel.AH_6, private val attack: Boolean = true) {
        val pad = helper.absoluteVec(Vec3(28.5, 1.0, 100.5))
        val heli = Ports.vehicles.create(helper.level, model)!!
        val crew = mutableListOf<NpcEntity>()
        val manager = SquadManager.get(helper.level)
        val squad: com.sbwnpc.squad.squad.Squad
        var enemy: NpcEntity? = null
        val airborne: Boolean get() = heli.y - Helicopters.groundY(helper.level, heli.x, heli.z) >= 8.0
        var onClose: () -> Unit = {}
        val trace = StringBuilder("tick,x,y,z,yaw,pitch,roll,speed,radius,seats,target,health,order\n")
        private val pacer = CombatTickPacer()
        private val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "flight-observer"))
        init {
            // Vanilla test barriers must not become heightmap terrain or a false ceiling for aircraft.
            StructureUtils.removeBarriers(helper.bounds, helper.level)
            for (x in 0..191) for (z in 0..191) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
            DebugFlags.enabledGroups = setOf(LogGroup.HELI, LogGroup.ORDER)
            heli.moveTo(pad.x, pad.y, pad.z, -90f, 0f)
            helper.level.addFreshEntity(heli)
            Ports.vehicles.fuelAndArm(heli, model)
            SquadTeams.assign(heli, SquadFaction.PIG)
            for ((index, role) in model.crew.withIndex()) {
                crew += AttackDeploymentGameTests.spawn(helper, Vec3(28.5 + index * 0.8, 1.0, 102.5), SquadFaction.PIG, role = role).also {
                    if (role in setOf(NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER)) it.assignedVehicleId = heli.uuid
                }
            }
            if (model == HelicopterModel.MI_28) {
                // Match SquadDeployment.spawnHelicopter: pilot first, then the permanent gunner.
                // Only the transport infantry boards asynchronously in the deployed preset.
                crew.forEach { helper.assertTrue(it.startRiding(heli, false), "Gunship preset could not seat its crew") }
            }
            squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, crew.map { it.uuid })
            manager.setOrder(squad.id, if (attack) SquadOrder.ATTACK else SquadOrder.RETREAT)
            manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(140, 1, 100)))
            witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            val point = helper.absoluteVec(Vec3(5.5, 5.0, 5.5))
            witness.moveTo(point.x, point.y, point.z, 0f, 0f)
            witness.setNoGravity(true)
            helper.level.addNewPlayer(witness)
            NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
            helper.testInfo.addListener(object : GameTestListener {
                override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
                override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = close()
                override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = close()
                override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = close()
            })
        }
        fun tick() {
            if (attack && enemy == null && Ports.vehicles.seating(heli).count { it != null } == 4) {
                enemy = AttackDeploymentGameTests.spawn(helper, Vec3(104.5, 1.0, 100.5), SquadFaction.COW).apply {
                    isNoAi = true
                    getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 100000.0
                    health = maxHealth
                }
            }
            helper.assertTrue(heli.isAlive && Ports.vehicles.healthFraction(heli) >= 0.9, "Transport collided or crashed")
            helper.assertTrue(crew.filter { it.isAlive }.all { it.health == it.maxHealth }, "Bench fire hurt a passenger")
            if (airborne) helper.assertTrue(crew.filter { it.isAlive }.all { it.vehicle === heli }, "Crew left an airborne transport")
            val pos = helper.relativeVec(heli.position())
            trace.append("${helper.tick},${pos.x},${pos.y},${pos.z},${heli.yRot},${heli.xRot},${Ports.vehicles.roll(heli)},${heli.deltaMovement.horizontalDistance()},${enemy?.let { heli.position().subtract(it.position()).horizontalDistance() }},${Ports.vehicles.seating(heli).count { it != null }},${crew.first().target != null},${Ports.vehicles.healthFraction(heli)},${squad.order}\n")
        }
        fun result(content: String) = write("${helper.testInfo.testName}-result.txt", content)
        private fun close() {
            onClose()
            NeoForge.EVENT_BUS.unregister(pacer)
            write("${helper.testInfo.testName}.csv", trace.toString())
            manager.deleteSquad(helper.level, squad.id)
            crew.forEach { it.discard() }
            enemy?.discard()
            heli.discard()
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
        }
        private fun write(name: String, content: String) {
            val path = Path.of(System.getProperty("sbwnpc.gameTestReports"), name)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
    }
    private fun wrapped(value: Double): Double {
        var v = value
        while (v > PI) v -= 2 * PI
        while (v < -PI) v += 2 * PI
        return v
    }
}
