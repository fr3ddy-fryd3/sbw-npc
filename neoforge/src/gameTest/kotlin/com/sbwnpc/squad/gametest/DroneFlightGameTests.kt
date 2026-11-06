package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

private fun atShelter(operator: NpcEntity, shelter: Vec3?): Boolean = shelter != null && operator.position().distanceTo(shelter) <= 1.0 && operator.navigation.isDone

@PrefixGameTestTemplate(false)
object DroneFlightGameTests {
    /** How long after the launch the operator is kept under fire and must stay at his monitor. */
    private const val UNDER_FIRE_TICKS = 60L

    /** A defensive post under broad cover, with a distant strike target confirmed by a spotter. */

    // Ticks he gets to cover most of the 8 blocks to his cover.
    private const val COVER_WALK_TICKS = 40L

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
            if (preparingAt == null && operator.holds(NpcActivity.DRONE) && operator.dronesLeft == 10) preparingAt = operator.position()
            val drone = roof.drone()
            if (helper.tick % 20L == 0L) scene.result("RUNNING", "tick=${helper.tick} operator=${operator.position()} home=$home busy=${operator.holds(NpcActivity.DRONE)} left=${operator.dronesLeft} launchedAt=$launchedAt drone=${drone?.position()}")
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

    /**
     * A roof wider than the short search leaves drones untouched and releases the operator between
     * tries: he does look for open sky, and gives up without spending a drone.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "drone-launch-closed")
    fun operatorWithNoNearbyOpenSkyKeepsDronesAndReleasesMovement(helper: GameTestHelper) {
        val roof = RoofLaunchScene(helper, "drone-launch-closed", 20)
        var freeTicks = 0
        var searched = false
        helper.onEachTick {
            roof.report()
            helper.assertTrue(roof.operator.dronesLeft == 10, "Searching without open sky consumed a drone")
            searched = searched || roof.operator.holds(NpcActivity.DRONE)
            if (helper.tick >= 100 && !roof.operator.holds(NpcActivity.DRONE)) freeTicks++
            if (helper.tick >= 300) {
                helper.assertTrue(searched, "He never looked for open sky, so nothing was released")
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
            if (orderedAt == null && roof.operator.holds(NpcActivity.DRONE) && roof.operator.dronesLeft == 10) {
                roof.manager.issueOrderAt(helper.level, roof.squad.id, SquadOrder.RETREAT, helper.absolutePos(BlockPos(100, 1, 20)))
                orderedAt = helper.tick
            }
            val since = orderedAt?.let { helper.tick - it } ?: return@onEachTick
            helper.assertTrue(roof.operator.dronesLeft == 10, "The operator launched instead of withdrawing")
            if (since > 5) helper.assertTrue(!roof.operator.holds(NpcActivity.DRONE), "The launch walk kept control after RETREAT")
            if (since >= 40) {
                roof.scene.result("PASS", "orderedAt=$orderedAt finishedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** An operator whose drone is up and who must take cover walks to it, and the drone stays up. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "drone-launch-cover")
    fun coverInterruptingLaunchReturnKeepsItsOwnPath(helper: GameTestHelper) {
        val roof = RoofLaunchScene(helper, "drone-launch-cover", 6)
        val coverOwner = Any()
        var cover: Vec3? = null
        var startGap = 0.0
        var interruptedAt: Long? = null
        helper.onEachTick {
            roof.report()
            if (interruptedAt == null && roof.drone() != null) {
                roof.operator.claim(NpcActivity.COVER, coverOwner)
                val point = roof.operator.position().add(8.0, 0.0, 0.0)
                roof.operator.navigation.moveTo(point.x, point.y, point.z, 1.0)
                helper.assertTrue(roof.operator.navigation.path != null, "The fixture could not establish its cover path")
                cover = point
                startGap = roof.operator.position().distanceTo(point)
                interruptedAt = helper.tick
            }
            val since = interruptedAt?.let { helper.tick - it } ?: return@onEachTick
            if (since >= COVER_WALK_TICKS) {
                val gap = roof.operator.position().distanceTo(cover!!)
                helper.assertTrue(gap < startGap - 4.0, "He did not walk to cover: ${"%.1f".format(startGap)} -> ${"%.1f".format(gap)}")
                helper.assertTrue(roof.drone() != null, "The drone came down when he went for cover")
                roof.scene.result("PASS", "interruptedAt=$interruptedAt gap=${"%.1f".format(startGap)}->${"%.1f".format(gap)}")
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
        // A drone strikes only an enemy known near the ATTACK point (9f8d4b5), never the bare point.
        val target = AttackDeploymentGameTests.spawn(helper, Vec3(150.5, 1.0, 100.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)!!.baseValue = 50000.0
            health = maxHealth
        }
        val heights = mutableListOf<Double>()
        val trace = StringBuilder("tick,x,height,vy\n")
        helper.onEachTick {
            TeamAwareness.report(SquadFaction.PIG, target.uuid, target.position(), helper.level.gameTime, "strike-test spotter")
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
        // A drone strikes only an enemy known near the ATTACK point (9f8d4b5), never the bare point.
        val target = AttackDeploymentGameTests.spawn(helper, Vec3(120.5, 1.0, 100.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)!!.baseValue = 50000.0
            health = maxHealth
        }
        var launchedAt: Vec3? = null
        helper.onEachTick {
            TeamAwareness.report(SquadFaction.PIG, target.uuid, target.position(), helper.level.gameTime, "strike-test spotter")
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

    /** An open field: an elite operator and two riflemen of one squad on DEFEND, and the enemies a spotter reports far off. */
    private class StrikeScene(val helper: GameTestHelper, name: String, squadless: Boolean = false) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val operator = scene.spawn(Vec3(20.5, 1.0, 100.5), NpcClass.DRONE_OPERATOR)
        val manager = SquadManager.get(helper.level)
        val riflemen = if (squadless) emptyList() else listOf(scene.spawn(Vec3(22.5, 1.0, 98.5)), scene.spawn(Vec3(22.5, 1.0, 102.5)))
        val squad = if (squadless) null else manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(operator.uuid) + riflemen.map { it.uuid })

        init {
            DebugFlags.enabledGroups = setOf(LogGroup.DRONE, LogGroup.FIRE_SUPPORT, LogGroup.ORDER)
            operator.npcRank = NpcRank.ELITE
            squad?.let { manager.issueOrderAt(helper.level, it.id, SquadOrder.DEFEND, operator.blockPosition()) }
            scene.onClose = { squad?.let { manager.deleteSquad(helper.level, it.id) } }
        }

        fun report(target: Entity) = TeamAwareness.report(SquadFaction.PIG, target.uuid, target.position(), helper.level.gameTime, "strike-test spotter")

        fun drone(): Entity? = helper.level.getEntities(operator, operator.boundingBox.inflate(200.0)) { Ports.drones.isDrone(it) }.firstOrNull()
    }

    /**
     * An operator launches, strikes and has his drone come to an end in the same team as his
     * riflemen: the strike is his own plan's method, not a move out of his team and back (#259).
     * Fire on him after the launch keeps him at the monitor and the drone flying (#259).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "drone-strike-team")
    fun operatorStaysWithHisTeamThroughAStrike(helper: GameTestHelper) {
        val strike = StrikeScene(helper, "drone-strike-team")
        val target = strike.scene.enemy(Vec3(150.5, 1.0, 100.5))
        var team: com.sbwnpc.squad.ai.planning.AgentId? = null
        var launchedAt: Long? = null
        helper.onEachTick {
            strike.report(target)
            // The squad re-cuts its teams when its duty changes on contact; the operator goes with
            // his riflemen, never into a team of his own for the strike.
            val now = com.sbwnpc.squad.ai.integration.NativeTactics.teamOf(strike.operator)
            val theirs = strike.riflemen.map { com.sbwnpc.squad.ai.integration.NativeTactics.teamOf(it) }
            if (now != null) team = now
            if (now != null && theirs.all { it != null }) helper.assertTrue(theirs.all { it == now }, "The operator's team $now left his riflemen's $theirs at tick ${helper.tick}, launched at $launchedAt")
            val drone = strike.drone()
            if (drone != null && launchedAt == null) launchedAt = helper.tick
            launchedAt?.let { helper.tick - it }?.takeIf { it <= UNDER_FIRE_TICKS }?.let { since ->
                if (since % 10 == 0L) strike.operator.suppress(strike.operator.position().add(-30.0, 1.6, 0.0))
                helper.assertTrue(drone != null && strike.operator.holds(NpcActivity.DRONE), "Fire took the operator off his drone $since ticks after launch")
            }
            if (launchedAt != null && drone == null && helper.tick - launchedAt!! > UNDER_FIRE_TICKS) {
                helper.assertTrue(team != null, "The operator was never planned in a team")
                strike.scene.result("PASS", "launchedAt=$launchedAt endedAt=${helper.tick} team=$team")
                helper.succeed()
            }
            if (helper.tick == 400L) helper.assertTrue(launchedAt != null, "The operator never launched at a reported target")
        }
    }

    /** A drone whose target dies under it takes the next one the plan gives it, instead of flying home (#259). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "drone-strike-next")
    fun droneWhoseTargetDiesTakesTheNextThroughThePlan(helper: GameTestHelper) {
        val strike = StrikeScene(helper, "drone-strike-next")
        val first = strike.scene.enemy(Vec3(150.5, 1.0, 100.5))
        var second: NpcEntity? = null
        var lastSeen: Vec3? = null
        helper.onEachTick {
            val next = second
            if (next == null) {
                strike.report(first)
                val drone = strike.drone() ?: return@onEachTick
                // Well on its way: the first target goes, and the side reports another.
                if (drone.position().distanceTo(strike.operator.position()) > 40.0) {
                    first.discard()
                    second = strike.scene.enemy(Vec3(130.5, 1.0, 130.5))
                }
                return@onEachTick
            }
            strike.report(next)
            val drone = strike.drone()
            // Gone before it got there: it ended somewhere, and only near the second target is
            // that the strike rather than a drone flown home, timed out or crashed on the way.
            if (drone == null) {
                val end = lastSeen?.distanceTo(next.position())
                helper.assertTrue(end != null && end <= 20.0, "The drone ended ${end?.let { "%.0f".format(it) }} blocks from the next target, not on it")
            }
            lastSeen = drone?.position() ?: lastSeen
            if (drone == null || drone.position().distanceTo(next.position()) <= 20.0) {
                strike.scene.result("PASS", "tick=${helper.tick} drone=${drone?.position()} last=$lastSeen left=${strike.operator.dronesLeft}")
                helper.succeed()
            }
        }
    }

    /** A squadless operator strikes what his side reports, in a plan of his own (#259). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "drone-strike-alone")
    fun squadlessOperatorStrikesAReportedTarget(helper: GameTestHelper) {
        val strike = StrikeScene(helper, "drone-strike-alone", squadless = true)
        val target = strike.scene.enemy(Vec3(120.5, 1.0, 100.5))
        helper.onEachTick {
            strike.report(target)
            if (strike.drone() != null) {
                strike.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }
}
