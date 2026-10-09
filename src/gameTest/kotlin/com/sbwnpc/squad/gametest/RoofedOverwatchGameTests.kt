package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Block-for-block copy of the player's roofed tower; template origin was (-55, -61, -30). */
@PrefixGameTestTemplate(false)
object RoofedOverwatchGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1200, batch = "roofed-tower")
    fun supportOccupiesPlayerRoofedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1200, batch = "roofed-patrol")
    fun patrolSupportOccupiesPlayerRoofedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.PATROL)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1600, batch = "roofed-fall")
    fun fallenSupportReturnsToPlayerRoofedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND, fall = true)

    private fun occupy(helper: GameTestHelper, order: SquadOrder, fall: Boolean = false) {
        // Template blocks start one block above the structure origin used by GameTestHelper.
        helper.assertTrue(helper.getBlockState(BlockPos(44, 1, 39)).`is`(Blocks.GRASS_BLOCK), "Copied ground is at the wrong height")
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.SNIPER, NpcClass.MACHINE_GUNNER, NpcClass.MEDIC)
        val places = listOf(
            Vec3(-12.286, -60.0, 26.408),
            Vec3(8.016, -60.0, 14.593),
            Vec3(0.059, -60.0, 7.5),
            Vec3(-33.511, -60.0, 5.273),
            Vec3(-13.627, -60.0, 6.38),
            Vec3(-10.692, -60.0, 9.646),
            Vec3(-29.385, -60.0, 3.448),
        )
        val npcs = places.mapIndexed { index, at ->
            AttackDeploymentGameTests.spawn(helper, at.add(55.0, 62.0, 30.0), SquadFaction.CAT, randomSeed = index.toLong(), role = roles[index])
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.CAT, npcs.map { it.uuid })
        manager.setOrder(squad.id, order)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(43, 1, 42)))
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = oldGroups + LogGroup.ORDER
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val trace = StringBuilder("tick,role,x,y,z,postX,postY,postZ,job,recovery,closest,lastProgress\n")
        val name = "roofed-tower-$order${if (fall) "-fall" else ""}"
        var climbed = false
        var settledSince: Long? = null
        var displaced: NpcEntity? = null
        var retainedPost: Vec3? = null
        var landed = false
        var removedFloor: BlockPos? = null
        var floorState: BlockState? = null
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                DebugFlags.enabledGroups = oldGroups
                manager.disband(helper.level, squad.id)
                npcs.forEach {
                    FiringSpots.release(it.uuid)
                    it.discard()
                }
            }
        })
        helper.onEachTick {
            val supports = npcs.filter { it.npcClass in setOf(NpcClass.SNIPER, NpcClass.MACHINE_GUNNER) }
            if (supports.any { it.onClimbable() }) climbed = true
            val high = supports.filter { npc ->
                val task = squad.tactics.plan?.tasks?.get(npc.uuid) ?: return@filter false
                val post = task.position ?: return@filter false
                val relative = helper.relativeVec(post)
                task.job == TacticalJob.OVERWATCH && !task.localFireRecovery && relative.y in 6.5..7.5 &&
                    FiringPostArrival.reached(npc.position(), post) && npc.onGround() && TacticalPositions.protected(npc, task, post)
            }
            if (helper.tick % 10L == 0L) {
                supports.forEach { npc ->
                    val task = squad.tactics.plan?.tasks?.get(npc.uuid)
                    val at = helper.relativeVec(npc.position())
                    val post = task?.position?.let(helper::relativeVec)
                    trace.append("${helper.tick},${npc.npcClass},${at.x},${at.y},${at.z},${post?.x},${post?.y},${post?.z},${task?.job},${task?.localFireRecovery},${task?.closest},${task?.lastProgress}\n")
                }
                Files.writeString(report.resolve("$name.csv"), trace)
            }
            displaced?.let { npc ->
                helper.assertTrue(npc.isAlive, "Displaced sentry died")
                val task = squad.tactics.plan?.tasks?.get(npc.uuid)
                helper.assertTrue(task != null && task.position == retainedPost && task.job == TacticalJob.OVERWATCH && !task.localFireRecovery, "Displacement discarded the sheltered platform")
                helper.assertTrue(retainedPost in FiringSpots.nearby(retainedPost!!, 1.0, UUID(0, 0)), "Displacement lost the platform reservation")
                if (!landed && npc.onGround() && helper.relativeVec(npc.position()).y < 3.0) {
                    landed = true
                    helper.level.setBlockAndUpdate(removedFloor!!, floorState!!)
                }
            }
            val arrived = if (displaced == null) high.isNotEmpty() else landed && displaced in high
            if (!arrived) {
                settledSince = null
            } else {
                if (settledSince == null) settledSince = helper.tick
                if (helper.tick - settledSince!! >= 100) {
                    helper.assertTrue(climbed, "No actual ladder ascent took place")
                    val post = squad.tactics.plan!!.tasks[high.first().uuid]!!.position!!
                    helper.assertTrue(post in FiringSpots.nearby(post, 1.0, UUID(0, 0)), "Occupied post is unreserved")
                    if (fall && displaced == null) {
                        displaced = high.first()
                        retainedPost = post
                        settledSince = null
                        // Remove the supporting floor until the sentry actually lands, then
                        // restore the same block: the walls/roof prevent a simple shove outside.
                        removedFloor = BlockPos.containing(post).below()
                        floorState = helper.level.getBlockState(removedFloor!!)
                        helper.level.setBlockAndUpdate(removedFloor!!, Blocks.AIR.defaultBlockState())
                        return@onEachTick
                    }
                    Files.writeString(report.resolve("$name-result.txt"), "settled=${high.size}\nclimbed=$climbed\npost=${helper.relativeVec(post)}\nlanded=$landed\n")
                    helper.succeed()
                }
            }
        }
    }
}
