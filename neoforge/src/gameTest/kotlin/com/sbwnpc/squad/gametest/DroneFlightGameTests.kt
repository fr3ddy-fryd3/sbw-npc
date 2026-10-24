package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.StructureUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

@PrefixGameTestTemplate(false)
object DroneFlightGameTests {
    private fun atShelter(operator: NpcEntity, shelter: Vec3?): Boolean = shelter != null && operator.position().distanceTo(shelter) <= 1.0 && operator.navigation.isDone

    /** A defensive post under broad cover, with a distant strike target confirmed by a spotter. */
    private class RoofLaunchScene(val helper: GameTestHelper, name: String, radius: Int) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val operator = scene.spawn(Vec3(20.5, 1.0, 100.5), NpcClass.DRONE_OPERATOR)
        val target = scene.enemy(Vec3(150.5, 1.0, 100.5))
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(operator.uuid))

        init {
            operator.npcRank = NpcRank.ELITE
            manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, operator.blockPosition())
            for (x in 20 - radius..20 + radius) for (z in 100 - radius..100 + radius) helper.setBlock(BlockPos(x, 4, z), Blocks.BEDROCK)
            scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        }

        /** Keeps the remote spotter's sighting fresh without moving the operator's defensive post. */
        fun report() = TeamAwareness.report(SquadFaction.PIG, target.uuid, target.position(), helper.level.gameTime, "launch-test spotter")

        fun drone(): Entity? = helper.level.getEntities(operator, operator.boundingBox.inflate(180.0)) { Ports.drones.isDrone(it) }.firstOrNull()
    }

    /** A defending operator leaves broad overhead cover to launch, then returns to his post. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "drone-launch-walk")
    fun defendingOperatorWalksOutFromBroadRoofAndReturnsAfterLaunch(helper: GameTestHelper) {
        val roof = RoofLaunchScene(helper, "drone-launch-walk", 6)
        val scene = roof.scene
        val operator = roof.operator
        val home = operator.position()
        var preparingAt: Vec3? = null
        var launchedAt: Vec3? = null
        helper.onEachTick {
            roof.report()
            helper.assertTrue(operator.isAlive, "The operator died while finding open sky")
            if (preparingAt == null && operator.operatingDrone && operator.dronesLeft == 10) preparingAt = operator.position()
            val drone = roof.drone()
            if (helper.tick % 20L == 0L) scene.result("RUNNING", "tick=${helper.tick} operator=${operator.position()} home=$home busy=${operator.operatingDrone} left=${operator.dronesLeft} launchedAt=$launchedAt drone=${drone?.position()}")
            if (drone != null && launchedAt == null) {
                launchedAt = operator.position()
                helper.assertTrue(home.distanceTo(operator.position()) > 3.0, "The drone launched through the broad roof")
            }
            if (launchedAt != null && atShelter(operator, preparingAt)) {
                helper.assertTrue(operator.dronesLeft == 9, "Preparing the launch consumed extra drones")
                scene.result("PASS", "tick=${helper.tick} launchedAt=$launchedAt returned=${operator.position()}")
                helper.succeed()
            }
            if (helper.tick == 400L) helper.assertTrue(launchedAt != null, "The operator never left the roof to launch")
        }
    }

    /** A roof wider than the short search leaves drones untouched and releases the operator between tries. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "drone-launch-closed")
    fun operatorWithNoNearbyOpenSkyKeepsDronesAndReleasesMovement(helper: GameTestHelper) {
        val roof = RoofLaunchScene(helper, "drone-launch-closed", 20)
        var freeTicks = 0
        helper.onEachTick {
            roof.report()
            helper.assertTrue(roof.operator.dronesLeft == 10, "Searching without open sky consumed a drone")
            if (helper.tick >= 100 && !roof.operator.operatingDrone) freeTicks++
            if (helper.tick >= 300) {
                helper.assertTrue(freeTicks >= 50, "A failed launch search kept the operator's movement indefinitely")
                roof.scene.result("PASS", "tick=${helper.tick} freeTicks=$freeTicks")
                helper.succeed()
            }
        }
    }

    /** RETREAT cancels the walk before launch, so the operator follows his squad without losing a drone. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "drone-launch-cancel")
    fun retreatCancelsAnOperatorsWalkToLaunch(helper: GameTestHelper) {
        val roof = RoofLaunchScene(helper, "drone-launch-cancel", 6)
        var orderedAt: Long? = null
        helper.onEachTick {
            roof.report()
            if (orderedAt == null && roof.operator.operatingDrone && roof.operator.dronesLeft == 10) {
                roof.manager.issueOrderAt(helper.level, roof.squad.id, SquadOrder.RETREAT, helper.absolutePos(BlockPos(100, 1, 20)))
                orderedAt = helper.tick
            }
            val since = orderedAt?.let { helper.tick - it } ?: return@onEachTick
            helper.assertTrue(roof.operator.dronesLeft == 10, "The operator launched instead of withdrawing")
            if (since > 5) helper.assertTrue(!roof.operator.operatingDrone, "The launch walk kept control after RETREAT")
            if (since >= 40) {
                roof.scene.result("PASS", "orderedAt=$orderedAt finishedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** An emergency cover path replacing the return route stays owned by cover on later flight ticks. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "drone-launch-cover")
    fun coverInterruptingLaunchReturnKeepsItsOwnPath(helper: GameTestHelper) {
        val roof = RoofLaunchScene(helper, "drone-launch-cover", 6)
        var coverPath: net.minecraft.world.level.pathfinder.Path? = null
        var interruptedAt: Long? = null
        helper.onEachTick {
            roof.report()
            if (interruptedAt == null && roof.drone() != null) {
                BrainUtils.setMemory(roof.operator, ModMemories.COVER_HOLD.get(), true)
                val cover = roof.operator.position().add(8.0, 0.0, 0.0)
                roof.operator.navigation.moveTo(cover.x, cover.y, cover.z, 1.0)
                coverPath = roof.operator.navigation.path
                helper.assertTrue(coverPath != null, "The fixture could not establish its cover path")
                interruptedAt = helper.tick
            }
            val since = interruptedAt?.let { helper.tick - it } ?: return@onEachTick
            helper.assertTrue(roof.operator.navigation.path === coverPath, "The drone flight stopped cover's replacement path")
            if (since >= 5) {
                roof.scene.result("PASS", "interruptedAt=$interruptedAt finishedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /**
     * A strike drone sent over a hill comes back down to its cruise height on the far side. It used
     * to hold the hill's height all the way, because its wanted height only came down 0.5 a tick,
     * inside the controller's deadband (#121).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 900, batch = "drone-ridge")
    fun strikeDroneComesDownAfterARidge(helper: GameTestHelper) {
        StructureUtils.removeBarriers(helper.bounds, helper.level)
        DebugFlags.enabledGroups = setOf(LogGroup.FIRE_SUPPORT)
        for (x in 0..191) for (z in 0..191) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // The same hill as the helicopter ridge scenes: 30 high at x 80, gone by x 115.
        for (x in 45..115) {
            val top = (30.0 * (1.0 - abs(x - 80) / 35.0)).toInt()
            for (z in 70..130) for (y in 1..top) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        }
        val operator = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 100.5), SquadFaction.PIG, role = NpcClass.DRONE_OPERATOR)
        // An elite operator reaches 160, so the objective 130 away can be struck.
        operator.npcRank = NpcRank.ELITE
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(operator.uuid))
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(150, 1, 100)))
        val heights = mutableListOf<Double>()
        val trace = StringBuilder("tick,x,height,vy\n")
        helper.onEachTick {
            // The operator's is the only drone in the arena.
            val drone = helper.level.getEntities(operator, operator.boundingBox.inflate(200.0)) { Ports.drones.isDrone(it) }.firstOrNull()
                ?: return@onEachTick
            val x = helper.relativeVec(drone.position()).x
            val height = drone.y - helper.level.getHeight(Heightmap.Types.MOTION_BLOCKING, drone.blockX, drone.blockZ)
            trace.append("${helper.tick},${"%.1f".format(x)},${"%.2f".format(height)},${"%.3f".format(drone.deltaMovement.y)}\n")
            // Past the hill and before the attack dive, which starts within 15 of the objective.
            if (x in 122.0..132.0) heights += height
            if (x > 132.0 && heights.isNotEmpty()) {
                Path.of(System.getProperty("sbwnpc.gameTestReports"), "${helper.testInfo.testName}.csv").also { Files.createDirectories(it.parent) }.let { Files.writeString(it, trace) }
                helper.assertTrue(heights.max() <= 24.0, "Drone crossed the flat past the hill ${heights.max().toInt()} blocks up")
                // Down to its cruise height, not bobbing or skimming the ground on the way.
                helper.assertTrue(heights.min() >= 6.0 && heights.max() - heights.min() <= 6.0, "Drone height past the hill ran ${heights.min().toInt()}..${heights.max().toInt()}")
                helper.succeed()
            }
        }
    }

    /**
     * An operator behind a wall, under a roof, launches his drone into open sky and lives (#158).
     * Put down a block and a half in front of him, it went into the wall and blew him up.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 900, batch = "drone-cover-launch")
    fun operatorInCoverLaunchesIntoOpenSky(helper: GameTestHelper) {
        StructureUtils.removeBarriers(helper.bounds, helper.level)
        DebugFlags.enabledGroups = setOf(LogGroup.DRONE)
        for (x in 0..191) for (z in 0..191) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // A wall two high right in front of him toward the target, and a roof over his head.
        for (z in 90..110) for (y in 1..2) helper.setBlock(BlockPos(22, y, z), Blocks.STONE)
        for (x in 19..21) for (z in 99..101) helper.setBlock(BlockPos(x, 4, z), Blocks.STONE)
        val operator = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 100.5), SquadFaction.PIG, role = NpcClass.DRONE_OPERATOR)
        operator.npcRank = NpcRank.ELITE
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(operator.uuid))
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(120, 1, 100)))
        var launchedAt: Vec3? = null
        helper.onEachTick {
            helper.assertTrue(operator.isAlive, "The operator died by tick ${helper.tick}, drone launched at $launchedAt")
            val drone = helper.level.getEntities(operator, operator.boundingBox.inflate(200.0)) { Ports.drones.isDrone(it) }.firstOrNull()
                ?: return@onEachTick
            val at = helper.relativeVec(drone.position())
            if (launchedAt == null) launchedAt = at
            if (at.y >= 12.0) {
                helper.assertTrue(abs(launchedAt!!.y - 3.0) <= 0.5, "The drone went up from $launchedAt, not 2 over the operator's feet")
                helper.succeed()
            }
        }
    }
}
