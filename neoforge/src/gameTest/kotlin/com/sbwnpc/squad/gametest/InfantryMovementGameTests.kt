package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
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
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

/** Exercises the production brain, order behaviour, navigator and movement controller together. */
@PrefixGameTestTemplate(false)
object InfantryMovementGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 600)
    fun moveAcrossFlatGround(helper: GameTestHelper) {
        floor(helper)
        march(helper, "flat-ground", BlockPos(20, 1, 6), needsClimb = false)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 600)
    fun moveOntoOneBlockPlatform(helper: GameTestHelper) {
        floor(helper)
        for (x in 12..23) {
            for (z in 0..11) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        }
        march(helper, "one-block-platform", BlockPos(20, 2, 6), needsClimb = true)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 600)
    fun aimingSidewaysDoesNotTrapNpcInShallowHole(helper: GameTestHelper) {
        floor(helper)
        helper.setBlock(BlockPos(11, 0, 6), Blocks.AIR)
        helper.setBlock(BlockPos(11, -1, 6), Blocks.STONE)
        for (y in 1..2) helper.setBlock(BlockPos(12, y, 6), Blocks.STONE)
        helper.setBlock(BlockPos(11, 1, 5), Blocks.STONE)
        for (x in 0..23) for (z in 7..11) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        march(helper, "side-aim-hole", BlockPos(10, 2, 8), needsClimb = true, opposedAim = true, start = Vec3(11.7, 0.0, 6.3))
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 600)
    fun marchRecoversPastChangedHeadroom(helper: GameTestHelper) {
        floor(helper)
        for (x in 12..23) for (z in 0..11) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        march(helper, "blocked-headroom", BlockPos(20, 2, 6), needsClimb = true, blockHeadroom = true)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 600)
    fun marchRecoversPastChangedHeadroomOnFarmland(helper: GameTestHelper) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.FARMLAND)
        for (x in 12..23) for (z in 0..11) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        march(helper, "farmland-headroom", BlockPos(20, 2, 6), needsClimb = true, blockHeadroom = true, partialFloor = true)
    }

    private fun floor(helper: GameTestHelper) {
        for (x in 0..23) {
            for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 600)
    fun marchAvoidsPowderSnowPatch(helper: GameTestHelper) {
        floor(helper)
        for (x in 9..13) {
            for (z in 4..8) {
                helper.setBlock(BlockPos(x, -1, z), Blocks.STONE)
                helper.setBlock(BlockPos(x, 0, z), Blocks.POWDER_SNOW)
            }
        }
        march(helper, "powder-snow-detour", BlockPos(20, 1, 6), needsClimb = false, avoidPowderSnow = true)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 600)
    fun marchEscapesDeepPowderSnowWithoutBreakingBlocks(helper: GameTestHelper) {
        floor(helper)
        for (x in 0..23) {
            for (z in 0..11) {
                for (y in 1..3) {
                    helper.setBlock(BlockPos(x, y, z), if (x in 8..12 && z in 4..8) Blocks.POWDER_SNOW else Blocks.STONE)
                }
            }
        }
        march(helper, "powder-snow-escape", BlockPos(20, 4, 6), needsClimb = true, start = Vec3(10.5, 1.0, 6.5), preservePowderSnow = true)
    }

    private fun march(
        helper: GameTestHelper,
        scenario: String,
        destination: BlockPos,
        needsClimb: Boolean,
        blockHeadroom: Boolean = false,
        partialFloor: Boolean = false,
        opposedAim: Boolean = false,
        avoidPowderSnow: Boolean = false,
        preservePowderSnow: Boolean = false,
        start: Vec3 = Vec3(3.5, 1.0, 6.5),
    ) {
        val level = helper.level
        val npc = helper.spawn(ModEntities.NPC.get(), start)
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
        val aim = if (opposedAim) helper.spawn(net.minecraft.world.entity.EntityType.MARKER, Vec3(20.5, 1.0, 6.5)) else null
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            manager.deleteSquad(level, squad.id)
            npc.discard()
            aim?.discard()
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
        })
        if (partialFloor) {
            helper.runAtTickTime(20) {
                val post = helper.absoluteVec(Vec3(8.5, 0.9375, 6.5))
                val path = TacticalPositions.pathToPosition(npc, post)
                helper.assertTrue(TacticalPositions.reaches(path, post), "A valid farmland firing post was rejected: reach=${path?.canReach()}, end=${path?.endNode?.asBlockPos()}")
            }
        }
        var climbed = false
        var blocked = false
        var lastProgress = 0L
        var progressPosition = npc.position()
        helper.onEachTick {
            // Reproduce the same body-turn request used by GunAttackBehaviour.updateAim,
            // while the normal MOVE brain and vanilla physics climb in the opposite direction.
            aim?.let { npc.lookAt(it, 30f, 30f) }
            if (aim != null) npc.navigation.setSpeedModifier(1.0)
            val relative = helper.relativeVec(npc.position())
            if (blockHeadroom && !blocked && relative.x >= 10.0 && npc.navigation.path != null) {
                // Change the world after the live route was chosen. The ends remain open for a detour.
                for (z in 4..8) helper.setBlock(BlockPos(11, 3, z), Blocks.STONE)
                blocked = true
            }
            trace.sample(helper.tick, helper.relativeVec(npc.position()), npc, goal)
            if (npc.position().subtract(progressPosition).horizontalDistanceSqr() >= 1.0) {
                progressPosition = npc.position()
                lastProgress = helper.tick
            }
            helper.assertTrue(helper.tick - lastProgress < 150 || npc.position().distanceTo(goal) <= 1.5, "MOVE stood still for 150 ticks; see $scenario.csv")
            if (npc.y >= helper.absolutePos(BlockPos(0, 2, 0)).y && npc.onGround()) climbed = true
            helper.assertTrue(npc.isAlive, "NPC died on a movement-only route")
            if (avoidPowderSnow) helper.assertTrue(!npc.isInPowderSnow, "MOVE entered a powder snow patch despite a dry detour")
            if (preservePowderSnow) {
                for (x in 8..12) {
                    for (z in 4..8) {
                        for (y in 1..3) {
                            helper.assertTrue(helper.getBlockState(BlockPos(x, y, z)).`is`(Blocks.POWDER_SNOW), "Snow escape broke terrain")
                        }
                    }
                }
            }
        }
        helper.succeedWhen {
            helper.assertTrue(npc.tickCount >= 20, "NPC must actually tick before arrival can pass")
            helper.assertTrue(npc.position().distanceTo(goal) <= 1.5, "NPC did not reach MOVE objective; see $scenario.csv")
            helper.assertTrue(!needsClimb || climbed, "NPC never stood on the raised platform")
            helper.assertTrue(!blockHeadroom || blocked, "The obstruction was never installed")
            trace.success(helper.tick)
            cleanup()
        }
        helper.runAtTickTime(599) {
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
            Files.writeString(file, "tick,npcTicks,x,y,z,distance,speed,vx,vy,vz,onGround,horizontalCollision,pose,pathDone,nextNode,nodeCount,canReach,wantedX,wantedY,wantedZ,gameTime,formationReady,rallySince,target,alert,resupply,coverLocked,medicLocked,bodyYaw,headYaw\n")
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
            val squad = npc.currentSquad()
            val diagnostics = "${npc.level().gameTime},${squad?.moveFormationReady},${squad?.moveRallySince},${npc.target?.uuid},${npc.isAlert()},${npc.resupplying},${npc.movementLockedByCover()},${npc.combatLockedByMedic()},${npc.yRot},${npc.yHeadRot}\n"
            Files.writeString(file, line.trimEnd() + "," + diagnostics, java.nio.file.StandardOpenOption.APPEND)
        }

        fun success(tick: Long) {
            Files.writeString(file.resolveSibling(file.fileName.toString().removeSuffix(".csv") + ".txt"), "result=PASS\ntick=$tick\n", java.nio.file.StandardOpenOption.APPEND)
        }

        fun failure(tick: Long, distance: Double) {
            Files.writeString(file.resolveSibling(file.fileName.toString().removeSuffix(".csv") + ".txt"), "result=FAIL\ntick=$tick\ndistance=$distance\n", java.nio.file.StandardOpenOption.APPEND)
        }
    }
}
