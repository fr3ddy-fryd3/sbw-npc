package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.tactics.TacticalAim
import com.sbwnpc.squad.combat.tactics.TacticalContact
import com.sbwnpc.squad.combat.tactics.TacticalFirePosts
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalMember
import com.sbwnpc.squad.combat.tactics.TacticalPositionSearch
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.combat.tactics.TacticalSnapshot
import com.sbwnpc.squad.combat.tactics.TacticalTask
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.nio.file.Files
import java.nio.file.Path

/** Replays the blocked three-block hill from the failed battle, without randomized deployment. */
@PrefixGameTestTemplate(false)
object FiringPostSearchGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 120, batch = "support-score")
    fun coverDeploymentMeasuresRealShelter(helper: GameTestHelper) = supportDeployment(helper, TacticalJob.COVER)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 120, batch = "support-score")
    fun waitDeploymentMeasuresRealShelter(helper: GameTestHelper) = supportDeployment(helper, TacticalJob.WAIT)

    private fun supportDeployment(helper: GameTestHelper, job: TacticalJob) {
        Files.createDirectories(Path.of(System.getProperty("sbwnpc.gameTestReports")))
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(30.5, 1.0, 100.5), SquadFaction.PIG).apply { isNoAi = true }
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(140.5, 1.0, 100.5), SquadFaction.COW).apply { isNoAi = true }
        val open = helper.absoluteVec(Vec3(47.5, 1.0, 100.5))
        val sheltered = helper.absoluteVec(Vec3(45.5, 1.0, 105.5))
        helper.setBlock(BlockPos(46, 1, 105), Blocks.STONE)
        val task = TacticalTask(job, open, enemy.position(), 1).apply { localFirePost = open }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                FiringSpots.release(npc.uuid)
                npc.discard()
                enemy.discard()
            }
        })
        helper.runAtTickTime(20) {
            npc.setOnGround(true)
            BrainUtils.setTargetOfEntity(npc, enemy)
            // Both posts have a real firing lane;
            // the exposed post is the assigned one, the other has a one-block wall.
            task.search = TacticalPositionSearch(npc.position(), listOf(open, sheltered), TacticalAim.resolve(npc, task))
        }
        helper.onEachTick {
            if (helper.tick <= 20) return@onEachTick
            npc.setOnGround(true)
            val view = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, npc.position(), enemy.position(), listOf(TacticalMember(npc.uuid, npc.position())), listOf(TacticalContact(enemy.uuid, enemy.position(), helper.level.gameTime)))
            val search = task.search!!
            val result = TacticalPositions.find(npc, task, view)
            if (result.outcome == TacticalPositions.Outcome.DEFERRED) return@onEachTick
            Files.writeString(Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("support-score-$job.txt"), "result=$result\nscores=${search.scored}\n")
            helper.assertTrue(search.scored.size == 2, "Fixture did not admit both posts: ${search.rejections}")
            helper.assertTrue(search.scored[open]?.open == true, "Exposed $job post received synthetic shelter: ${search.scored}")
            helper.assertTrue(result.position == sheltered, "$job selected an exposed post despite reachable shelter: $result")
            helper.succeed()
        }
    }

    /**
     * Of two posts with a clear shot by collision, the one in the middle of tall grass is turned
     * down: kneeling there, the man sees nobody to shoot (#159).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 120, batch = "grass-post")
    fun coverSearchPassesOverAPostInTallGrass(helper: GameTestHelper) {
        Files.createDirectories(Path.of(System.getProperty("sbwnpc.gameTestReports")))
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (x in 45..52) {
            for (z in 98..103) {
                // Grass doesn't stand on stone.
                helper.setBlock(BlockPos(x, 0, z), Blocks.GRASS_BLOCK)
                helper.setBlock(BlockPos(x, 1, z), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER))
                helper.setBlock(BlockPos(x, 2, z), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER))
            }
        }
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(30.5, 1.0, 105.5), SquadFaction.PIG).apply { isNoAi = true }
        // Within rifle range, so the post must have a shot to be taken at all.
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(100.5, 1.0, 100.5), SquadFaction.COW).apply { isNoAi = true }
        val grassy = helper.absoluteVec(Vec3(47.5, 1.0, 100.5))
        val open = helper.absoluteVec(Vec3(47.5, 1.0, 110.5))
        val task = TacticalTask(TacticalJob.COVER, grassy, enemy.position(), 1).apply { localFirePost = grassy }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                FiringSpots.release(npc.uuid)
                npc.discard()
                enemy.discard()
            }
        })
        helper.runAtTickTime(20) {
            npc.setOnGround(true)
            BrainUtils.setTargetOfEntity(npc, enemy)
            task.search = TacticalPositionSearch(npc.position(), listOf(grassy, open), TacticalAim.resolve(npc, task))
        }
        helper.onEachTick {
            if (helper.tick <= 20) return@onEachTick
            npc.setOnGround(true)
            val view = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, npc.position(), enemy.position(), listOf(TacticalMember(npc.uuid, npc.position())), listOf(TacticalContact(enemy.uuid, enemy.position(), helper.level.gameTime)))
            val search = task.search!!
            val result = TacticalPositions.find(npc, task, view)
            if (result.outcome == TacticalPositions.Outcome.DEFERRED) return@onEachTick
            Files.writeString(Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("grass-post.txt"), "result=$result\nscores=${search.scored}\nrejections=${search.rejections}\n")
            helper.assertTrue(result.position == open, "The search chose $result over the open post: ${search.rejections}")
            helper.assertTrue(search.rejections["blocked_shot"] == 1, "The grassy post was not turned down for its shot: ${search.rejections}")
            helper.assertFalse(TacticalPositions.firingReady(npc, task, grassy), "A post in tall grass counts as ready to fire from")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 120, batch = "advance-progress")
    fun advanceSearchRejectsAlreadyReachedIntermediatePost(helper: GameTestHelper) {
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(30.5, 1.0, 100.5), SquadFaction.PIG).apply { isNoAi = true }
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(140.5, 1.0, 20.5), SquadFaction.COW).apply { isNoAi = true }
        helper.setBlock(BlockPos(32, 1, 99), Blocks.STONE)
        helper.setBlock(BlockPos(32, 1, 100), Blocks.STONE)
        val origin = npc.position()
        val goal = origin.add(6.433, 10.0, -6.555)
        val task = TacticalTask(TacticalJob.ADVANCE, goal, enemy.position(), 1).apply {
            localFirePost = goal
            // Isolate validation of a reached post; the natural scene exercises probe generation.
            search = TacticalPositionSearch(origin, listOf(origin), TacticalAim.choose(null, enemy.position()))
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                FiringSpots.release(npc.uuid)
                npc.discard()
                enemy.discard()
            }
        })
        helper.onEachTick {
            npc.setOnGround(true)
            val view = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, origin, enemy.position(), listOf(TacticalMember(npc.uuid, origin)), listOf(TacticalContact(enemy.uuid, enemy.position(), helper.level.gameTime)))
            val result = TacticalPositions.find(npc, task, view)
            if (result.outcome == TacticalPositions.Outcome.DEFERRED) return@onEachTick
            Files.writeString(Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("advance-progress.txt"), "origin=$origin\ngoal=$goal\nresult=$result\n")
            if (result.outcome == TacticalPositions.Outcome.FOUND) {
                val point = result.position!!
                helper.assertTrue(point.distanceTo(goal) <= 10.0 || point.distanceTo(origin) >= 0.5, "Search returned the already reached intermediate post outside arrival tolerance: $point")
            } else {
                helper.assertTrue(result.rejections["no_goal_progress"] == 1, "Reached post was rejected for an unrelated reason: ${result.rejections}")
            }
            helper.succeed()
        }
    }

    /** A post planned at another height than the ground at its spot goes onto that ground (#55). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 20, batch = "post-ground", skyAccess = true)
    fun plannedPostOffTheGroundGoesOntoIt(helper: GameTestHelper) {
        for (x in 0..15) for (z in 0..15) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // A canopy over one spot, a hill over another.
        for (x in 9..11) for (z in 2..4) helper.setBlock(BlockPos(x, 6, z), Blocks.OAK_LEAVES)
        for (x in 2..4) for (z in 9..11) for (y in 1..5) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        // A pond over a cave: the lakebed is not footing, and neither is the cave floor under it.
        for (x in 12..14) {
            for (z in 12..14) {
                helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
                helper.setBlock(BlockPos(x, 4, z), Blocks.STONE)
                helper.setBlock(BlockPos(x, 5, z), Blocks.WATER)
            }
        }
        fun post(x: Double, y: Double, z: Double) = com.sbwnpc.squad.combat.tactics.DryGround.near(helper.level, helper.absoluteVec(Vec3(x, y, z)), helper.absoluteVec(Vec3(x, 1.0, z)))
        val ground = helper.absoluteVec(Vec3(8.3, 1.0, 8.7))
        helper.assertTrue(post(8.3, 10.0, 8.7) == ground, "A post 9 blocks up stayed off the ground: ${post(8.3, 10.0, 8.7)}")
        helper.assertTrue(post(8.3, 14.0, 8.7) == ground, "A post 13 blocks up stayed off the ground: ${post(8.3, 14.0, 8.7)}")
        helper.assertTrue(post(10.3, 9.0, 3.7) == helper.absoluteVec(Vec3(10.3, 1.0, 3.7)), "A post over a canopy went onto the leaves: ${post(10.3, 9.0, 3.7)}")
        helper.assertTrue(post(3.3, 2.0, 10.7) == helper.absoluteVec(Vec3(3.3, 6.0, 10.7)), "A post inside a hill did not go onto it: ${post(3.3, 2.0, 10.7)}")
        // Sky access: the barrier roof GameTest puts over a closed scene would be the first floor below.
        helper.assertTrue(post(8.3, 45.0, 8.7) == ground, "A post 44 blocks up stayed off the ground: ${post(8.3, 45.0, 8.7)}")
        val pond = post(13.3, 9.0, 13.7)
        val inPond = helper.relativeVec(pond).let { it.x in 12.0..15.0 && it.z in 12.0..15.0 }
        helper.assertTrue(!inPond && !helper.level.getBlockState(BlockPos.containing(pond).below()).isAir, "A post over a pond did not go to dry ground beside it: $pond")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 200, batch = "firing-search")
    fun firingSearchFindsReachablePostBesideBlockedHill(helper: GameTestHelper) {
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        TerrainAttackGameTests.unevenGround(helper)
        val posts = listOf(
            Vec3(13.580244, 1.0, 24.458235) to Vec3(100.5, 1.0, 92.5),
            Vec3(50.497190, 2.0, 93.499335) to Vec3(100.5, 1.0, 98.5),
            Vec3(47.496930, 3.0, 120.357982) to Vec3(100.5, 1.0, 104.5),
            Vec3(59.618451, 1.0, 142.455423) to Vec3(100.5, 1.0, 104.5),
        )
        val actors = posts.map { AttackDeploymentGameTests.spawn(helper, it.first, SquadFaction.PIG).apply { isNoAi = true } }
        val enemies = (0..3).map { AttackDeploymentGameTests.spawn(helper, Vec3(100.5, 1.0, 92.5 + it * 6), SquadFaction.COW).apply { isNoAi = true } }
        for ((post, target) in posts) {
            val forward = target.subtract(post).multiply(1.0, 0.0, 1.0).normalize()
            val side = Vec3(-forward.z, 0.0, forward.x)
            for (depth in 2..16) {
                for (lateral in -3..3) {
                    val base = BlockPos.containing(post.add(forward.scale(depth.toDouble())).add(side.scale(lateral.toDouble())))
                    for (dy in 0..3) {
                        val block = base.above(dy)
                        val body = AABB(helper.absolutePos(block))
                        if ((actors + enemies).none { it.boundingBox.inflate(4.0).intersects(body) }) helper.setBlock(block, Blocks.STONE)
                    }
                }
            }
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() =
                (actors + enemies).forEach {
                    FiringSpots.release(it.uuid)
                    it.discard()
                }
        })
        val npc = actors[2]
        val target = enemies[2]
        val task = TacticalTask(TacticalJob.COVER, npc.position(), target.position(), 1).apply {
            localFirePost = npc.position()
            expandedFireSearch = true
        }
        fun view() =
            TacticalSnapshot(
                helper.level.gameTime,
                SquadOrder.ATTACK,
                1,
                npc.position(),
                target.position(),
                actors.map { TacticalMember(it.uuid, it.position()) },
                enemies.map { TacticalContact(it.uuid, it.position(), helper.level.gameTime) },
            )
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"), "blocked-hill-search.txt")
        Files.createDirectories(report.parent)
        helper.runAtTickTime(20) {
            // NoAI skips the travel tick that normally initializes this flag. Verify physical
            // support before enabling grounded path queries for this stationary replay.
            helper.assertTrue(!helper.level.noCollision(npc, npc.boundingBox.move(0.0, -0.01, 0.0)), "Replay shooter has no supporting block")
            npc.setOnGround(true)
            BrainUtils.setTargetOfEntity(npc, target)
            fun ready(raw: Vec3): Vec3? {
                val feet = Terrain.feetAt(helper.level, raw) { helper.level.noCollision(npc, npc.getDimensions(npc.pose).makeBoundingBox(it)) } ?: return null
                return feet.takeIf { TacticalPositions.safeDistance(npc, view(), it) && TacticalPositions.firingReady(npc, task, it) }
            }
            val coarse = TacticalFirePosts.probes(npc.position(), recovery = true).mapNotNull(::ready).distinct()
            val dense = (-24..24).flatMap { x ->
                (-24..24).mapNotNull { z ->
                    if (x * x + z * z <= 24 * 24) ready(npc.position().add(x.toDouble(), 0.0, z.toDouble())) else null
                }
            }.distinct().sortedBy { it.distanceToSqr(npc.position()) }
            val reachable = dense.filter { point ->
                val path = TacticalPositions.pathToPosition(npc, point)
                TacticalPositions.reaches(path, point) && TacticalPositions.safePath(npc, view(), path!!)
            }
            val samples = dense.take(8).map { point ->
                val path = TacticalPositions.pathToPosition(npc, point)
                "${helper.relativeVec(point)} reach=${path?.canReach()} end=${path?.endNode?.asBlockPos()}"
            }
            Files.writeString(report, "ground=${npc.onGround()}\norigin=${helper.relativeVec(npc.position())}\ncoarseReady=${coarse.size}\ndenseReady=${dense.size}\ndenseReachable=${reachable.size}\nsamples=$samples\nnearest=${reachable.take(8).map(helper::relativeVec)}\n")
            helper.assertTrue(reachable.isNotEmpty(), "Replay contains no reachable firing post in 24 blocks; see $report")
        }
        helper.onEachTick {
            if (helper.tick <= 20) return@onEachTick
            val result = TacticalPositions.find(npc, task, view())
            helper.assertTrue(result.outcome != TacticalPositions.Outcome.UNREACHABLE, "Search missed an independently verified reachable firing post; see $report")
            if (result.outcome == TacticalPositions.Outcome.FOUND) {
                helper.assertTrue(TacticalPositions.firingReady(npc, task, result.position!!), "Selected post has no shot")
                helper.assertTrue(task.localFirePost == task.anchor, "Selecting a post moved the fixed escape origin back to the requested line")
                Files.writeString(report, "result=PASS\ntick=${helper.tick}\nselected=${helper.relativeVec(result.position)}\n", java.nio.file.StandardOpenOption.APPEND)
                helper.succeed()
            }
        }
    }
}
