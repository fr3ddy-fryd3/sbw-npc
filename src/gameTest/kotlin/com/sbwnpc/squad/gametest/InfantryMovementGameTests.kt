package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.tactics.TacticalEvent
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalRuntimeEvents
import com.sbwnpc.squad.combat.tactics.TacticalTask
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

/** Exercises the production brain, order behaviour, navigator and movement controller together. */
@GameTestHolder("sbwnpc")
@PrefixGameTestTemplate(false)
object InfantryMovementGameTests {
    @JvmStatic
    @GameTest(template = "movement_arena", timeoutTicks = 400)
    fun moveAcrossFlatGround(helper: GameTestHelper) {
        floor(helper)
        march(helper, "flat-ground", BlockPos(20, 1, 6), needsClimb = false)
    }

    @JvmStatic
    @GameTest(template = "movement_arena", timeoutTicks = 400)
    fun moveOntoOneBlockPlatform(helper: GameTestHelper) {
        floor(helper)
        for (x in 12..23) {
            for (z in 0..11) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        }
        march(helper, "one-block-platform", BlockPos(20, 2, 6), needsClimb = true)
    }

    @JvmStatic
    @GameTest(template = "movement_arena", timeoutTicks = 400)
    fun taskReleaseDuringJumpKeepsCurrentPath(helper: GameTestHelper) {
        floor(helper)
        for (x in 12..23) for (z in 0..11) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        march(helper, "task-release-in-air", BlockPos(20, 2, 6), needsClimb = true, releaseInAir = true)
    }

    @JvmStatic
    @GameTest(template = "movement_arena", timeoutTicks = 400)
    fun marchRecoversPastChangedHeadroom(helper: GameTestHelper) {
        floor(helper)
        for (x in 12..23) for (z in 0..11) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        march(helper, "blocked-headroom", BlockPos(20, 2, 6), needsClimb = true, blockHeadroom = true)
    }

    private fun floor(helper: GameTestHelper) {
        for (x in 0..23) {
            for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        }
    }

    private fun march(
        helper: GameTestHelper,
        scenario: String,
        destination: BlockPos,
        needsClimb: Boolean,
        releaseInAir: Boolean = false,
        blockHeadroom: Boolean = false,
    ) {
        val level = helper.level
        val npc = helper.spawn(ModEntities.NPC.get(), Vec3(3.5, 1.0, 6.5))
        npc.npcClass = NpcClass.RIFLEMAN
        npc.npcRank = NpcRank.DEFAULT
        npc.spawnFaction = SquadFaction.PIG
        npc.finalizeSpawn(level, level.getCurrentDifficultyAt(npc.blockPosition()), MobSpawnType.COMMAND, null)
        val manager = SquadManager.get(level)
        val squad = manager.create(level, UUID.randomUUID(), SquadFaction.PIG, listOf(npc.uuid))
        manager.setOrder(squad.id, SquadOrder.MOVE)
        manager.setObjective(level, squad.id, helper.absolutePos(destination))
        // Deliberately disable real AI in one route to prove the arrival check detects a stall.
        // This switch exists only in the test source set, never in the shipped mod.
        if (scenario == "flat-ground" && java.lang.Boolean.getBoolean("sbwnpc.gameTestNegativeControl")) npc.isNoAi = true

        val objective = helper.absoluteVec(Vec3.atBottomCenterOf(destination))
        val assembly = squad.moveAssembly?.center ?: objective
        // MOVE ends in the member's grid slot; even a one-man squad has an offset from the map point.
        val goal = SquadFormation.moveSlotTarget(npc, objective, objective.subtract(assembly))
        helper.assertTrue(npc.currentSquad()?.id == squad.id, "Test NPC was not registered in its squad")
        helper.assertTrue(!npc.mainHandItem.isEmpty, "SBW failed to issue the rifleman's weapon")
        val trace = MovementTrace(scenario, npc)
        var climbed = false
        var released = false
        var blocked = false
        TacticalRuntimeEvents.bind(squad, level)
        helper.onEachTick {
            val relative = helper.relativeVec(npc.position())
            if (blockHeadroom && !blocked && relative.x >= 10.0 && npc.navigation.path != null) {
                // Change the world after the live route was chosen. The ends remain open for a detour.
                for (z in 4..8) helper.setBlock(BlockPos(11, 3, z), Blocks.STONE)
                blocked = true
            }
            if (releaseInAir && !released && !npc.onGround() && npc.deltaMovement.y > 0.0 && relative.x > 10.0) {
                val path = npc.navigation.path
                helper.assertTrue(path != null && !path.isDone, "Jump must be following a live route")
                val task = TacticalTask(
                    TacticalJob.COVER,
                    goal,
                    null,
                    1,
                ).also { it.navigationPath = path }
                squad.tactics.events.emit(
                    TacticalEvent.TaskReleased(
                        1,
                        level.gameTime,
                        npc.uuid,
                        task,
                        "test_phase_changed",
                    ),
                )
                helper.assertTrue(npc.navigation.path === path, "Ordinary task release interrupted a physical jump")
                released = true
            }
            trace.sample(helper.tick, helper.relativeVec(npc.position()), npc, goal)
            if (npc.y >= helper.absolutePos(BlockPos(0, 2, 0)).y && npc.onGround()) climbed = true
            helper.assertTrue(npc.isAlive, "NPC died on a movement-only route")
        }
        helper.succeedWhen {
            helper.assertTrue(npc.tickCount >= 20, "NPC must actually tick before arrival can pass")
            helper.assertTrue(npc.position().distanceTo(goal) <= 1.5, "NPC did not reach MOVE objective; see $scenario.csv")
            helper.assertTrue(!needsClimb || climbed, "NPC never stood on the raised platform")
            helper.assertTrue(!releaseInAir || released, "The route never exercised airborne task release")
            helper.assertTrue(!blockHeadroom || blocked, "The obstruction was never installed")
            trace.success(helper.tick)
            manager.deleteSquad(level, squad.id)
            npc.discard()
        }
        helper.runAtTickTime(399) {
            trace.failure(helper.tick, npc.position().distanceTo(goal))
            helper.fail("MOVE route timed out: distance=${npc.position().distanceTo(goal)}, pathDone=${npc.navigation.isDone}; see $scenario.csv")
        }
    }

    /** Flush each sample so a timeout or server crash preserves the last navigation state. */
    private class MovementTrace(scenario: String, npc: NpcEntity) {
        private val file = Path.of(System.getProperty("sbwnpc.gameTestReports"), "$scenario.csv")

        init {
            com.mojang.logging.LogUtils.getLogger().info("Headless movement scenario={}, reports={}, noAi={}", scenario, file.toAbsolutePath(), npc.isNoAi)
            Files.createDirectories(file.parent)
            Files.writeString(file, "tick,npcTicks,x,y,z,distance,speed,vx,vy,vz,onGround,horizontalCollision,pose,pathDone,nextNode,nodeCount,canReach,wantedX,wantedY,wantedZ\n")
            Files.writeString(file.resolveSibling("$scenario.txt"), "npc=${npc.uuid}\nscenario=$scenario\nnoAi=${npc.isNoAi}\nnavigation=${npc.navigation.javaClass.name}\nmoveControl=${npc.moveControl.javaClass.name}\n")
        }

        fun sample(tick: Long, relative: Vec3, npc: NpcEntity, goal: Vec3) {
            val path = npc.navigation.path
            val velocity = npc.deltaMovement
            val control = npc.moveControl
            val line = String.format(
                Locale.ROOT,
                "%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%s,%s,%d,%d,%s,%.4f,%.4f,%.4f%n",
                tick, npc.tickCount, relative.x, relative.y, relative.z, npc.position().distanceTo(goal), npc.speed,
                velocity.x, velocity.y, velocity.z, npc.onGround(), npc.horizontalCollision, npc.pose,
                npc.navigation.isDone, path?.nextNodeIndex ?: -1, path?.nodeCount ?: 0, path?.canReach() ?: false,
                control.wantedX, control.wantedY, control.wantedZ,
            )
            Files.writeString(file, line, java.nio.file.StandardOpenOption.APPEND)
        }

        fun success(tick: Long) {
            Files.writeString(file.resolveSibling(file.fileName.toString().removeSuffix(".csv") + ".txt"), "result=PASS\ntick=$tick\n", java.nio.file.StandardOpenOption.APPEND)
        }

        fun failure(tick: Long, distance: Double) {
            Files.writeString(file.resolveSibling(file.fileName.toString().removeSuffix(".csv") + ".txt"), "result=FAIL\ntick=$tick\ndistance=$distance\n", java.nio.file.StandardOpenOption.APPEND)
        }
    }
}
