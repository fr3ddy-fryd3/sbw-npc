package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.ai.integration.NativeAssignment
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.combat.tactics.TacticalTask
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer

/** Sentries on small lookouts: the cover step toward the enemy (#265) and two men to a side. */
@PrefixGameTestTemplate(false)
object LookoutGameTests {
    /**
     * A sentry on an open lookout, the enemy already inside his clearance, takes the parapet on
     * its near edge, 3 blocks nearer the enemy (#265). Before, that step counted as an approach
     * and was refused, and he fought from the open far edge.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "near-parapet")
    fun sentryStepsTowardTheEnemyToTheParapetOfHisLookout(helper: GameTestHelper) {
        buildLookout(helper)
        val sentry = AttackDeploymentGameTests.spawn(helper, Vec3(9.5, 7.0, 64.5), SquadFaction.PIG, randomSeed = 265L, role = NpcClass.MACHINE_GUNNER)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(sentry.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(11, 1, 64)))
        // On a pillar, so the far edge sees him over the parapet: the open spot is a real firing post.
        for (y in 1..3) helper.setBlock(BlockPos(48, y, 64), Blocks.STONE)
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(48.5, 4.0, 64.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 10000.0
            health = maxHealth
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                enemy.discard()
                manager.disband(helper.level, squad.id)
                FiringSpots.release(sentry.uuid)
                sentry.discard()
            }
        })
        sentry.yRot = -90f
        sentry.yHeadRot = -90f
        // Held still until he has the enemy: the step must be taken in contact, not before it.
        val speed = sentry.getAttribute(Attributes.MOVEMENT_SPEED)!!
        val pace = speed.baseValue
        speed.baseValue = 0.0
        var heldSince: Long? = null
        helper.onEachTick {
            if (speed.baseValue == 0.0) {
                if (sentry.target == null) return@onEachTick
                speed.baseValue = pace
            }
            val at = helper.relativeVec(sentry.position())
            helper.assertTrue(at.x <= 14.0 && at.y >= 6.5, "Sentry left his lookout: $at")
            if (at.x < 12.0 || !shelteredOnOverwatch(sentry)) {
                heldSince = null
                return@onEachTick
            }
            if (heldSince == null) heldSince = helper.tick
            if (helper.tick - heldSince!! >= 60) helper.succeed()
        }
    }

    /**
     * Six machine gunners on DEFEND under a 5x5 lookout walled all round. At the ground spacing of
     * 3 blocks only its four corners took a man, one to a side; on a height post two share a side.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "crowded-lookout")
    fun sentriesShareTheSidesOfALookout(helper: GameTestHelper) {
        buildWalledLookout(helper)
        val sentries = (0 until 6).map { AttackDeploymentGameTests.spawn(helper, Vec3(64.5 + it % 3, 1.0, 62.5 + it / 3 * 3), SquadFaction.PIG, randomSeed = 300L + it, role = NpcClass.MACHINE_GUNNER) }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, sentries.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(68, 1, 64)))
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                manager.disband(helper.level, squad.id)
                sentries.forEach {
                    FiringSpots.release(it.uuid)
                    it.discard()
                }
            }
        })
        var settledSince: Long? = null
        val onPost = HashSet<NpcEntity>()
        helper.onEachTick {
            for (man in onPost) helper.assertTrue(helper.relativeVec(man.position()).y >= 9.5, "A posted sentry was crowded off the lookout")
            val up = sentries.filter { helper.relativeVec(it.position()).y >= 9.5 && posted(it) }
            val posts = up.mapNotNull { NativeTactics.assignment(it)?.position }
            for (a in posts.indices) for (b in 0 until a) helper.assertTrue(posts[a].distanceTo(posts[b]) >= FiringSpots.HEIGHT_POST_SPACING - 0.01, "Two sentries' posts overlap: ${posts.map(helper::relativeVec)}")
            onPost += up
            if (up.size < 5) {
                settledSince = null
                return@onEachTick
            }
            if (settledSince == null) settledSince = helper.tick
            if (helper.tick - settledSince!! >= 100) helper.succeed()
        }
    }

    /** A 5x5 lookout floored at y 9, walled one block high all round, a ladder through its floor. */
    private fun buildWalledLookout(helper: GameTestHelper) {
        for (x in 40..100) for (z in 40..90) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (x in 70..76) {
            for (z in 61..67) {
                helper.setBlock(BlockPos(x, 9, z), Blocks.STONE)
                if (x in listOf(70, 76) || z in listOf(61, 67)) helper.setBlock(BlockPos(x, 10, z), Blocks.STONE)
            }
        }
        for (y in 1..8) helper.setBlock(BlockPos(73, y, 64), Blocks.STONE)
        for (y in 1..9) helper.setBlock(BlockPos(72, y, 64), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST))
    }

    /** Standing on his overwatch post, and that post sheltered. */
    private fun posted(npc: NpcEntity): Boolean {
        val post = NativeTactics.assignment(npc)?.position ?: return false
        return FiringPostArrival.reached(npc.position(), post) && shelteredOnOverwatch(npc)
    }

    /** An open 7x7 lookout 6 high with a parapet along its east (near) edge and its two near corners. */
    private fun buildLookout(helper: GameTestHelper) {
        for (x in 0..80) for (z in 40..90) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (x in 8..14) for (z in 61..67) helper.setBlock(BlockPos(x, 6, z), Blocks.STONE)
        for (z in 61..67) helper.setBlock(BlockPos(14, 7, z), Blocks.STONE)
        for (x in 12..13) for (z in listOf(61, 67)) helper.setBlock(BlockPos(x, 7, z), Blocks.STONE)
    }

    private fun shelteredOnOverwatch(npc: NpcEntity): Boolean {
        val assignment = NativeTactics.assignment(npc) ?: return false
        return assignment.job == TacticalJob.OVERWATCH && TacticalPositions.protected(npc, assignment.task(), npc.position())
    }

    /** The legacy [TacticalTask] shape of an HTN [NativeAssignment]. */
    private fun NativeAssignment.task() =
        TacticalTask(job, anchor, focus, 0).also {
            it.position = position
            it.localFirePost = localFirePost
        }
}
