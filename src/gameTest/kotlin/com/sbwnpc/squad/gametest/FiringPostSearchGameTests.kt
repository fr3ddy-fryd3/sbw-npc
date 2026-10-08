package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.tactics.TacticalContact
import com.sbwnpc.squad.combat.tactics.TacticalFirePosts
import com.sbwnpc.squad.combat.tactics.TacticalFrontPost
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalMember
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
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 100, batch = "flank-search")
    fun flankSearchGoesAroundSecondaryContact(helper: GameTestHelper) {
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(30.5, 1.0, 112.5), SquadFaction.PIG).apply { isNoAi = true }
        val enemies = listOf(Vec3(130.5, 1.0, 112.5), Vec3(60.5, 1.0, 109.5)).map {
            AttackDeploymentGameTests.spawn(helper, it, SquadFaction.COW).apply { isNoAi = true }
        }
        val task = TacticalTask(TacticalJob.FLANK, helper.absoluteVec(Vec3(74.5, 1.0, 48.5)), enemies.first().position(), 1)
        fun view() =
            TacticalSnapshot(
                helper.level.gameTime,
                SquadOrder.ATTACK,
                1,
                npc.position(),
                task.focus,
                listOf(TacticalMember(npc.uuid, npc.position())),
                enemies.map { TacticalContact(it.uuid, it.position(), helper.level.gameTime) },
            )
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() =
                (listOf(npc) + enemies).forEach {
                    FiringSpots.release(it.uuid)
                    it.discard()
                }
        })
        helper.runAtTickTime(20) {
            npc.setOnGround(true)
            BrainUtils.setTargetOfEntity(npc, enemies.first())
            // Verify a real safe detour before asking the production search to find its next leg.
            val detour = npc.position().add(-4.0, 0.0, -20.0)
            val path = TacticalPositions.pathToPosition(npc, detour)
            helper.assertTrue(TacticalPositions.safeDistance(npc, view(), detour), "Fixture has no safe detour point")
            helper.assertTrue(TacticalPositions.reaches(path, detour) && TacticalPositions.safePath(npc, view(), path!!), "Fixture has no reachable safe detour")
        }
        helper.onEachTick {
            if (helper.tick <= 20) return@onEachTick
            val result = TacticalPositions.find(npc, task, view())
            helper.assertTrue(result.outcome != TacticalPositions.Outcome.UNREACHABLE, "Flank rejected a reachable detour: ${result.rejections}")
            if (result.outcome == TacticalPositions.Outcome.FOUND) {
                val point = result.position!!
                helper.assertTrue(TacticalPositions.safeDistance(npc, view(), point), "Flank approached a secondary contact")
                helper.assertTrue(TacticalPositions.safePath(npc, view(), npc.navigation.path!!), "Flank route crosses a secondary contact")
                helper.assertTrue(point.distanceTo(npc.position()) >= 4.0, "Flank search returned no travel progress")
                helper.succeed()
            }
        }
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
            frontage = TacticalFrontPost(npc.position().add(12.0, 0.0, 0.0), target.position().subtract(npc.position()).multiply(1.0, 0.0, 1.0).normalize())
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
