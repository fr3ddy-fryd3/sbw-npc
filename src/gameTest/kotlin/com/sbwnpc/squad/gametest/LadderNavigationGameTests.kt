package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Real brains traverse a ten-block ladder; a route result alone cannot pass the arrival check. */
@PrefixGameTestTemplate(false)
object LadderNavigationGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "scaffolding_arena", timeoutTicks = 500, batch = "ladder")
    fun marchClimbsAndDescendsLadder(helper: GameTestHelper) {
        tower(helper)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(4.5, 1.0, 6.5), SquadFaction.PIG)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(npc.uuid))
        manager.setOrder(squad.id, SquadOrder.MOVE)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(14, 11, 6)))
        var climbed = false
        var onLadderTicks = 0
        var replanned = false
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val trace = StringBuilder("tick,x,y,z,climbable,onGround,node,target\n")
        helper.runAtTickTime(10) {
            val path = (npc.navigation as VehicleAwareNavigation).createPositionPath(helper.absolutePos(BlockPos(14, 11, 6)))
            Files.writeString(report.resolve("ladder-planned-path.txt"), "reachable=${path?.canReach()}\nnodes=${path?.let { p -> (0 until p.nodeCount).map { p.getNode(it).asBlockPos() } }}\n")
            helper.assertTrue(path?.canReach() == true, "No path to ladder platform; see ladder-planned-path.txt")
        }
        helper.onEachTick {
            val pos = helper.relativeVec(npc.position())
            trace.append("${helper.tick},${pos.x},${pos.y},${pos.z},${npc.onClimbable()},${npc.onGround()},${npc.navigation.path?.takeUnless { it.isDone }?.nextNodePos},${npc.navigation.path?.target}\n")
            if (npc.onClimbable()) {
                onLadderTicks++
                if (!replanned && pos.y > 4.0 && !climbed) {
                    val nav = npc.navigation as VehicleAwareNavigation
                    helper.assertTrue(nav.canPlan, "Ladder ascent was treated as a ballistic jump")
                    val path = nav.createPositionPath(helper.absolutePos(BlockPos(14, 11, 6)))
                    helper.assertTrue(path?.canReach() == true && path.nodeCount < 30, "Mid-ladder replanning fell back to the ground: $path")
                    helper.assertTrue(kotlin.math.abs(path!!.getNode(0).y - npc.y) <= 1.0, "Replanned route starts at the bottom instead of the NPC's current height")
                    replanned = nav.moveTo(path, 0.5)
                    helper.assertTrue(replanned, "Mid-ladder path handoff was refused")
                }
            }
            if (!climbed && pos.y >= 10.5 && pos.x >= 13.5 && npc.onGround()) {
                climbed = true
                manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(4, 1, 6)))
            }
            if (helper.tick % 20L == 0L) Files.writeString(report.resolve("ladder-round-trip.csv"), trace)
            helper.assertTrue(npc.isAlive && npc.health == npc.maxHealth, "Ladder route caused fall damage")
            val home = npc.homeCenter()!!
            val returnSlot = SquadFormation.moveSlotTarget(npc, home, home.subtract(Vec3.atBottomCenterOf(squad.moveAssembly!!)))
            if (helper.tick == 400L) Files.writeString(report.resolve("ladder-arrival.txt"), "climbed=$climbed\nhome=$home\nreturnSlot=$returnSlot\nposition=${npc.position()}\ndistance=${npc.position().distanceTo(returnSlot)}\nground=${npc.onGround()} done=${npc.navigation.isDone}\n")
            if (climbed && pos.y < 1.6 && npc.position().distanceTo(returnSlot) < 1.5 && npc.onGround() && npc.navigation.isDone) {
                helper.assertTrue(onLadderTicks >= 60 && replanned, "Route did not physically exercise ladder traversal")
                Files.writeString(report.resolve("ladder-round-trip.csv"), trace)
                manager.disband(helper.level, squad.id)
                npc.discard()
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "scaffolding_arena", timeoutTicks = 40, batch = "broken-ladder")
    fun brokenLadderDoesNotInventClimbingEdges(helper: GameTestHelper) {
        tower(helper)
        for (y in 4..6) helper.setBlock(BlockPos(11, y, 6), Blocks.AIR)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(4.5, 1.0, 6.5), SquadFaction.PIG).apply { isNoAi = true }
        helper.runAtTickTime(10) {
            npc.setOnGround(true)
            val path = (npc.navigation as VehicleAwareNavigation).createPositionPath(helper.absolutePos(BlockPos(14, 11, 6)))
            helper.assertTrue(path?.canReach() != true, "Path crossed three missing ladder blocks")
            npc.discard()
            helper.succeed()
        }
    }

    fun tower(helper: GameTestHelper) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (y in 1..10) {
            helper.setBlock(BlockPos(12, y, 6), Blocks.STONE)
            helper.setBlock(BlockPos(11, y, 6), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST))
        }
        for (x in 12..16) for (z in 4..8) helper.setBlock(BlockPos(x, 10, z), Blocks.STONE)
    }
}
