package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** Two squads attacking one point keep their posts apart (#299). */
@PrefixGameTestTemplate(false)
object PostSpacingGameTests {
    /**
     * A squad that has reached its attack point and is still fighting there (not yet on DEFEND)
     * while a second squad attacks the same point: in a playtest men of the two stood on
     * neighbouring blocks. Reports the closest pair of standing men, within a squad and between them.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1300, batch = "joint-attack-post-spacing")
    fun squadsAttackingOnePointHoldPostsApart(helper: GameTestHelper) = jointAttackSpacing(helper, size = 4, wall = false)

    /** The same with six men a squad and a knee-high wall across the point: cover pulls posts together. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1300, batch = "joint-attack-post-spacing-wall")
    fun squadsAttackingOneCoveredPointHoldPostsApart(helper: GameTestHelper) = jointAttackSpacing(helper, size = 6, wall = true)

    private fun jointAttackSpacing(helper: GameTestHelper, size: Int, wall: Boolean) {
        floor(helper)
        if (wall) for (z in 48..80) helper.setBlock(BlockPos(88, 1, z), Blocks.STONE)
        val objective = BlockPos(84, 1, 64)
        val first = (0 until size).map { AttackDeploymentGameTests.spawn(helper, Vec3(80.5 + (it / 2) * 2, 1.0, 62.5 + (it % 2) * 2), SquadFaction.PIG) }
        val second = (0 until size).map { AttackDeploymentGameTests.spawn(helper, Vec3(20.5 + (it / 2) * 3, 1.0, 60.5 + (it % 2) * 3), SquadFaction.PIG) }
        val targets = (0..3).map { index ->
            AttackDeploymentGameTests.spawn(helper, Vec3(116.5, 1.0, 55.5 + index * 6), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
            }
        }
        val manager = SquadManager.get(helper.level)
        val squads = listOf(first, second).mapIndexed { index, men ->
            manager.create(helper.level, UUID(4501, index.toLong()), SquadFaction.PIG, men.map { it.uuid }).also {
                manager.setOrder(it.id, SquadOrder.ATTACK)
                manager.setObjective(helper.level, it.id, helper.absolutePos(objective))
            }
        }
        val men = first + second
        val spacing = Spacing(first, second)
        helper.onEachTick { if (helper.tick % 20L == 0L) spacing.sample(helper) }
        helper.runAtTickTime(1250) {
            try {
                println("[joint-post-spacing] result worstWithin=${"%.2f".format(spacing.worstWithin)} worstBetween=${"%.2f".format(spacing.worstBetween)}")
                helper.assertTrue(spacing.worstWithin >= POST_SPACING, "Two standing men of one squad came ${"%.2f".format(spacing.worstWithin)} blocks apart")
                helper.assertTrue(spacing.worstBetween >= POST_SPACING, "Standing men of the two squads came ${"%.2f".format(spacing.worstBetween)} blocks apart")
                helper.succeed()
            } finally {
                squads.forEach { manager.deleteSquad(helper.level, it.id) }
                (men + targets).forEach { it.discard() }
            }
        }
    }

    private fun floor(helper: GameTestHelper) {
        for (x in 0..159) {
            for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        }
    }

    /** The closest pairs of men standing still for a second, within a squad and between the two, from tick 600 on. */
    private class Spacing(private val first: List<NpcEntity>, private val second: List<NpcEntity>) {
        private val last = HashMap<UUID, Vec3>()
        var worstWithin = Double.MAX_VALUE
            private set
        var worstBetween = Double.MAX_VALUE
            private set

        fun sample(helper: GameTestHelper) {
            val men = first + second
            // A man counts once he has stood still for a second: walking past a neighbour is not a post.
            val standing = men.filter { it.isAlive && last[it.uuid]?.let { p -> p.distanceTo(it.position()) < 0.3 } == true }.map { it.uuid }.toSet()
            men.forEach { last[it.uuid] = it.position() }
            val within = minOf(closest(first, first, standing), closest(second, second, standing))
            val between = closest(first, second, standing)
            if (helper.tick >= 600) {
                worstWithin = minOf(worstWithin, within)
                worstBetween = minOf(worstBetween, between)
            }
            if (helper.tick % 100L == 0L) {
                println(
                    "[joint-post-spacing] tick=${helper.tick} standing=${standing.size} within=${"%.2f".format(within)} between=${"%.2f".format(between)} " +
                        "first=${first.map { helper.relativePos(it.blockPosition()).toShortString() }} second=${second.map { helper.relativePos(it.blockPosition()).toShortString() }}",
                )
            }
        }

        private fun closest(a: List<NpcEntity>, b: List<NpcEntity>, standing: Set<UUID>): Double = a.flatMap { x -> b.filter { it !== x && x.uuid in standing && it.uuid in standing }.map { x.position().subtract(it.position()).horizontalDistance() } }.minOrNull() ?: Double.MAX_VALUE
    }

    /** Pushing alone keeps two bodies about a block apart; a post of one's own is farther. */
    private const val POST_SPACING = 2.0
}
