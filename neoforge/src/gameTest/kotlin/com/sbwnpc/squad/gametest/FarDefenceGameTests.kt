package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.DefensiveOverwatch
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
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** Defenders sent to a DEFEND order set far from where they stand (#166, #266). */
@PrefixGameTestTemplate(false)
object FarDefenceGameTests {
    /** They walk to the far defence instead of searching round it for a post as high as their tower. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "far-defend-tower")
    fun towerSentriesLeaveForAFarDefence(helper: GameTestHelper) {
        for (x in 0..175) for (z in 0..95) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (y in 1..10) {
            helper.setBlock(BlockPos(20, y, 48), Blocks.STONE)
            helper.setBlock(BlockPos(19, y, 48), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST))
        }
        for (x in 20..22) for (z in 47..49) helper.setBlock(BlockPos(x, 10, z), Blocks.STONE)
        val npcs = listOf(Vec3(21.5, 11.0, 47.5), Vec3(21.5, 11.0, 49.5)).mapIndexed { i, at ->
            AttackDeploymentGameTests.spawn(helper, at, SquadFaction.PIG, randomSeed = 140L + i, role = if (i == 0) NpcClass.MACHINE_GUNNER else NpcClass.SNIPER)
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, npcs.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        val anchor = helper.absoluteVec(Vec3(130.5, 1.0, 48.5))
        manager.setObjective(helper.level, squad.id, BlockPos.containing(anchor))
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.PATH)
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
            // No block of the tower is within the overwatch radius of the anchor.
            if (npcs.all { it.position().subtract(anchor).horizontalDistance() <= DefensiveOverwatch.RADIUS }) helper.succeed()
        }
    }

    /**
     * Riflemen sent to defend a point 90 blocks off meet an enemy on the way (#266). They fight
     * where the contact found them, from posts of their own: every post round them lay outside
     * their area and was refused, and they stood bunched in the open short of it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "far-defend-contact")
    fun defendersInContactOnTheWayFindPosts(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "far-defend-contact")
        val npcs = (0 until 4).map { scene.spawn(Vec3(20.5, 1.0, 40.5 + it * 3)) }
        // He fires back but never dies, so the contact lasts the whole way.
        val enemy = scene.enemy(Vec3(35.5, 1.0, 80.5)).apply { isInvulnerable = true }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(npcs.first().spawnFaction), npcs.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        val anchor = helper.absoluteVec(Vec3(110.5, 1.0, 45.5))
        manager.setObjective(helper.level, squad.id, BlockPos.containing(anchor))
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        var contactAt: Long? = null
        val start = npcs.associateWith { it.position().distanceTo(anchor) }
        helper.onEachTick {
            scene.tick(squad)
            if (contactAt == null && npcs.any { it.target === enemy }) contactAt = helper.tick
            val outOfArea = npcs.flatMap { man -> DecisionTrace.decisionsOf(man.uuid).filter { it.event == "post_failed" && ONLY_ORDER.containsMatchIn(it.detail) } }
            helper.assertTrue(outOfArea.isEmpty(), "Every post a defender searched lay outside his area: ${outOfArea.firstOrNull()?.detail}")
            val since = contactAt ?: return@onEachTick
            if (helper.tick - since >= CONTACT_TICKS) {
                val stacked = npcs.flatMap { a -> npcs.filter { it !== a && it.distanceTo(a) < STACKED } }
                helper.assertTrue(stacked.isEmpty(), "Defenders stood stacked: ${stacked.size / 2} pairs")
                // His posts never take him farther from the point he defends than he started.
                helper.assertTrue(npcs.all { it.position().distanceTo(anchor) <= start.getValue(it) + STACKED }, "A defender drifted away from his area")
                scene.result("PASS", "contactAt=$since distances=${npcs.map { "%.0f".format(it.position().distanceTo(anchor)) }}")
                helper.succeed()
            }
        }
    }

    /** How long into the contact the scene watches their post searches. */
    private const val CONTACT_TICKS = 200L

    /** A post search refused for being out of the order's area and for nothing else. */
    private val ONLY_ORDER = Regex("""rejected=\{order=\d+\}""")

    /** Two men nearer than this stand in each other. */
    private const val STACKED = 1.5
}
