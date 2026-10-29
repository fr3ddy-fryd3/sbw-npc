package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.entity.ai.VehicleAwareNodeEvaluator
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@PrefixGameTestTemplate(false)
object ScaffoldingGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "scaffolding_arena", timeoutTicks = 900, batch = "scaffold-march")
    fun marchClimbsHoldsAndDescendsScaffolding(helper: GameTestHelper) = march(helper, false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "scaffolding_arena", timeoutTicks = 900, batch = "scaffold-handoff")
    fun newOrderReversesScaffoldingAscent(helper: GameTestHelper) = march(helper, true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "scaffolding_arena", timeoutTicks = 40, batch = "scaffold-roof")
    fun scaffoldRouteCannotPassSolidRoof(helper: GameTestHelper) {
        for (x in 0..23) {
            for (z in 0..11) {
                helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
                helper.setBlock(BlockPos(x, 6, z), Blocks.STONE)
            }
        }
        for (y in 1..5) helper.setBlock(BlockPos(11, y, 6), Blocks.SCAFFOLDING)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(4.5, 1.0, 6.5), SquadFaction.PIG).apply { isNoAi = true }
        helper.runAtTickTime(10) {
            npc.setOnGround(true)
            val path = (npc.navigation as VehicleAwareNavigation).createPositionPath(helper.absolutePos(BlockPos(11, 7, 6)))
            helper.assertTrue(path?.canReach() != true, "Scaffold clearance bypassed a solid roof")
            npc.discard()
            helper.succeed()
        }
    }

    /**
     * A shaft beside a raised block: the old rule let the route step off the shaft one block down onto
     * the block, the follower cut that corner too early, and the man climbed back up (#178). From a
     * scaffolding node the evaluator must offer no sideways step to lower ground, only a level one.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "scaffolding_arena", timeoutTicks = 40, batch = "scaffold-exit")
    fun scaffoldingNodeOffersNoSidewaysStepDown(helper: GameTestHelper) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (y in 1..10) helper.setBlock(BlockPos(11, y, 6), Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, y == 1))
        for (x in 12..14) for (z in 5..7) for (y in 1..4) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(11.5, 6.0, 6.5), SquadFaction.PIG).apply { isNoAi = true }
        helper.runAtTickTime(10) {
            val at = npc.blockPosition()
            val evaluator = VehicleAwareNodeEvaluator()
            evaluator.prepare(PathNavigationRegion(helper.level, at.offset(-16, -16, -16), at.offset(16, 16, 16)), npc)
            val start = evaluator.start
            val out = arrayOfNulls<Node>(32)

            @Suppress("UNCHECKED_CAST")
            val count = evaluator.getNeighbors(out as Array<Node>, start)
            val neighbours = (0 until count).map { out[it]!!.asBlockPos() }
            val down = neighbours.filter { it.y < start.y && !helper.level.getBlockState(it).`is`(Blocks.SCAFFOLDING) }
            evaluator.done()
            npc.discard()
            helper.assertTrue(helper.level.getBlockState(at).`is`(Blocks.SCAFFOLDING), "The man is not inside the shaft")
            helper.assertTrue(down.isEmpty(), "Shaft node ${start.asBlockPos()} offers sideways steps down: $down of $neighbours")
            helper.succeed()
        }
    }

    private fun march(helper: GameTestHelper, reverse: Boolean) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (y in 1..10) helper.setBlock(BlockPos(11, y, 6), Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, y == 1))
        for (x in 12..16) for (z in 4..8) helper.setBlock(BlockPos(x, 10, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(4.5, 1.0, 6.5), SquadFaction.PIG, randomSeed = 200)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(npc.uuid))
        manager.setOrder(squad.id, SquadOrder.MOVE)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(14, 11, 6)))
        var heldSince: Long? = null
        var returning = false
        var climbed = false
        var descended = false
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val name = if (reverse) "scaffold-reverse" else "scaffold-roundtrip"
        val trace = StringBuilder("tick,x,y,z,climbable,descending,ground,health\n")
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                manager.disband(helper.level, squad.id)
                npc.discard()
            }
        })
        helper.runAtTickTime(10) {
            val path = (npc.navigation as VehicleAwareNavigation).createPositionPath(helper.absolutePos(BlockPos(14, 11, 6)))
            Files.writeString(report.resolve("$name-entry-path.txt"), "reachable=${path?.canReach()}\nnodes=${path?.let { p -> (0 until p.nodeCount).map { p.getNode(it).asBlockPos() } }}\n")
            helper.assertTrue(path?.canReach() == true, "No path up scaffold; see entry-path.txt")
        }
        helper.onEachTick {
            val at = helper.relativeVec(npc.position())
            if (npc.onClimbable() && at.y > 3) climbed = true
            if (returning && npc.onClimbable() && npc.deltaMovement.y < -0.01) descended = true
            trace.append("${helper.tick},${at.x},${at.y},${at.z},${npc.onClimbable()},${npc.isDescending},${npc.onGround()},${npc.health}\n")
            if (helper.tick % 10L == 0L) Files.writeString(report.resolve("$name.csv"), trace)
            helper.assertTrue(npc.isAlive && npc.health == npc.maxHealth, "Scaffolding traversal caused fall damage")
            val reachedTop = at.y >= 10.9 && npc.onGround() && npc.navigation.isDone
            if (!returning && reachedTop) {
                if (heldSince == null) heldSince = helper.tick
                // Crouching pose must not imply the collision intent to descend through a floor.
                npc.pose = Pose.CROUCHING
                helper.assertTrue(!npc.isDescending, "Holding a crouched post enables scaffold descent")
            }
            val ready = if (reverse) climbed && at.y in 4.0..7.0 else heldSince?.let { helper.tick - it >= 100 } == true
            if (!returning && ready) {
                returning = true
                manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(4, 1, 6)))
                val path = (npc.navigation as VehicleAwareNavigation).createPositionPath(helper.absolutePos(BlockPos(4, 1, 6)))
                Files.writeString(report.resolve("$name-exit-path.txt"), "reachable=${path?.canReach()}\n")
            }
            val home = npc.homeCenter()!!
            val slot = SquadFormation.moveSlotTarget(npc, home, home.subtract(Vec3.atBottomCenterOf(squad.moveAssembly!!)))
            if (returning && at.y < 1.6 && npc.onGround() && npc.position().distanceTo(slot) <= 1.5 && npc.navigation.isDone) {
                helper.assertTrue(climbed && descended && !npc.isDescending, "Ascent/descent/handoff not completed")
                Files.writeString(report.resolve("$name-result.txt"), "arrived=${helper.tick}\nheldSince=$heldSince\n")
                helper.succeed()
            }
        }
    }
}
